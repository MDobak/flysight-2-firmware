/***************************************************************************
**                                                                        **
**  FlySight 2 firmware                                                   **
**  Copyright 2026 Bionic Avionics Inc.                                   **
**                                                                        **
**  This program is free software: you can redistribute it and/or modify  **
**  it under the terms of the GNU General Public License as published by  **
**  the Free Software Foundation, either version 3 of the License, or     **
**  (at your option) any later version.                                   **
**                                                                        **
**  This program is distributed in the hope that it will be useful,       **
**  but WITHOUT ANY WARRANTY; without even the implied warranty of        **
**  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         **
**  GNU General Public License for more details.                          **
**                                                                        **
**  You should have received a copy of the GNU General Public License     **
**  along with this program.  If not, see <http://www.gnu.org/licenses/>. **
**                                                                        **
****************************************************************************
**  Contact: Bionic Avionics Inc.                                         **
**  Website: http://flysight.ca/                                          **
****************************************************************************/

#include <stdio.h>
#include <string.h>

#include "main.h"
#include "app_common.h"
#include "alt_ref.h"
#include "app_fatfs.h"
#include "audio.h"
#include "audio_control.h"
#include "config.h"
#include "config_ext.h"
#include "led.h"
#include "mode.h"
#include "replay_mode.h"
#include "replay_parse.h"
#include "resource_manager.h"
#include "state.h"
#include "stm32_seq.h"

#define TICK_MSEC    20
#define TICK_TICKS   (TICK_MSEC*1000/CFG_TS_TICK_VAL)
#define SEEK_BATCH   25     /* GNSS samples in one task call while seeking */
#define END_MSEC     5000   /* Time for the last audio after the end of the track */
#define ZERO_MSEC    60000  /* Look for the ground level only in the first minute */
#define LINE_LEN     160

typedef struct
{
	FIL  file;
	char buf[512];
	UINT len;
	UINT pos;
	bool open;
	bool eof;
} Reader_t;

/* Values from FS_ReplayMode_Set */
static char     folder[FS_REPLAY_FOLDER_LEN + 1];
static uint16_t offset_s;
static uint8_t  flags;

static FS_ReplayMode_Status_t status;

static Reader_t track;
static Reader_t sensor;

static bool running;
static bool fatfs;
static bool audio;
static bool timer;
static uint8_t timer_id;
static volatile uint32_t ticks;

static FS_AltRef_Zero_t savedZero;

/* GNSS samples. current stays the same until the audio reads it. */
static FS_GNSS_Data_t current;
static FS_GNSS_Data_t next;
static int64_t nextMs;
static bool hasNext;
static int64_t startMs;
static int64_t playBaseMs;
static uint32_t endTick;
static bool zeroDone;

/* Barometer */
static bool timeValid;
static int64_t timeUs;
static int64_t timeGps;
static bool baroPending;
static bool baroSeen;
static int64_t baroGps;
static int64_t baroUs;
static int32_t baroPressure;

static bool readerOpen(Reader_t *r, const char *path)
{
	r->len = 0;
	r->pos = 0;
	r->eof = false;
	r->open = (f_open(&r->file, path, FA_READ) == FR_OK);
	return r->open;
}

static void readerClose(Reader_t *r)
{
	if (r->open)
	{
		f_close(&r->file);
		r->open = false;
	}
}

/* Read one line without the line end. A long line is cut. Returns false
   at the end of the file. */
static bool readerLine(Reader_t *r, char *line, size_t size)
{
	size_t n = 0;
	bool any = false;

	if (!r->open) return false;

	for (;;)
	{
		if (r->pos >= r->len)
		{
			if (r->eof) break;
			r->pos = 0;
			if ((f_read(&r->file, r->buf, sizeof(r->buf), &r->len) != FR_OK) || (r->len == 0))
			{
				r->len = 0;
				r->eof = true;
				break;
			}
		}

		const char c = r->buf[r->pos++];
		any = true;
		if (c == '\n') break;
		if (n + 1 < size) line[n++] = c;
	}

	line[n] = '\0';
	return any;
}

static void readGNSS(void)
{
	char line[LINE_LEN];

	hasNext = false;
	while (readerLine(&track, line, sizeof(line)))
	{
		if (ReplayParse_GNSS(line, &next, &nextMs))
		{
			hasNext = true;
			break;
		}
	}
}

/* Give the barometer measurements up to the GPS time until_ms to alt_ref.c */
static void feedBaro(int64_t until_ms)
{
	char line[LINE_LEN];
	int64_t t, g;
	int32_t p;

	for (;;)
	{
		if (!baroPending)
		{
			if (!readerLine(&sensor, line, sizeof(line))) return;

			if (ReplayParse_Time(line, &t, &g))
			{
				timeValid = true;
				timeUs = t;
				timeGps = g;
				continue;
			}

			/* A barometer measurement before the first time pulse has no GPS time */
			if (!timeValid || !ReplayParse_Baro(line, &t, &p)) continue;

			baroUs = t;
			baroPressure = p;
			baroGps = timeGps + (t - timeUs) / 1000;
			baroPending = true;
			baroSeen = true;
		}

		if (baroGps > until_ms) return;

		FS_AltRef_UpdateBaro(baroPressure, (uint64_t) baroUs);
		baroPending = false;
	}
}

static void stepSample(bool play)
{
	const FS_Config_Data_t *config = FS_Config_Get();

	current = next;

	feedBaro(nextMs);
	FS_AltRef_UpdateGNSS(&current, FS_ConfigExt_Get()->alt_zero != 0);

	if ((flags & FS_REPLAY_FLAG_ZERO) && !zeroDone)
	{
		const uint8_t need = FS_ALT_REF_GNSS | (baroSeen ? FS_ALT_REF_BARO : 0);
		if (((FS_AltRef_SetZero() & need) == need) || (nextMs - startMs > ZERO_MSEC))
		{
			zeroDone = true;
		}
	}

	status.pos_ms = (uint32_t) (nextMs - startMs);
	status.height = FS_AltRef_Height(&current, FS_ConfigExt_Get()->alt_source,
			config->dz_elev, &status.kind);

	if (play && audio)
	{
		FS_AudioControl_UpdateGNSS(&current);
	}

	readGNSS();
}

static void finish(FS_ReplayMode_State_t state, FS_ReplayMode_Error_t error)
{
	status.state = state;
	status.error = error;
	endTick = ticks;

	if (state == FS_REPLAY_STATE_ERROR)
	{
		FS_LED_SetColour(FS_LED_RED);
		FS_LED_On();
		running = false;
		FS_Mode_PushQueue(FS_MODE_EVENT_EXT_STOP);
	}
}

static void replayTask(void)
{
	uint8_t i;

	if (!running) return;

	switch (status.state)
	{
	case FS_REPLAY_STATE_SEEKING:
		for (i = 0; (i < SEEK_BATCH) && hasNext &&
				(nextMs - startMs < (int64_t) offset_s * 1000); ++i)
		{
			stepSample(false);
		}

		if (!hasNext)
		{
			finish(FS_REPLAY_STATE_DONE, FS_REPLAY_ERROR_NONE);
		}
		else if (nextMs - startMs >= (int64_t) offset_s * 1000)
		{
			status.state = FS_REPLAY_STATE_PLAYING;
			playBaseMs = nextMs - startMs;
			ticks = 0;
		}
		else
		{
			/* Continue in the next task call. Other tasks can run between. */
			UTIL_SEQ_SetTask(1<<CFG_TASK_FS_REPLAY_ID, CFG_SCH_PRIO_1);
		}
		break;

	case FS_REPLAY_STATE_PLAYING:
		if (hasNext && (nextMs - startMs <= playBaseMs + (int64_t) ticks * TICK_MSEC))
		{
			/* One sample in each call. The audio reads it before the next call. */
			stepSample(true);
		}

		if (!hasNext)
		{
			finish(FS_REPLAY_STATE_DONE, FS_REPLAY_ERROR_NONE);
		}
		break;

	case FS_REPLAY_STATE_DONE:
		if ((ticks - endTick) * TICK_MSEC >= END_MSEC)
		{
			running = false;
			FS_Mode_PushQueue(FS_MODE_EVENT_EXT_STOP);
		}
		break;

	default:
		break;
	}
}

static void replayTimer(void)
{
	++ticks;
	UTIL_SEQ_SetTask(1<<CFG_TASK_FS_REPLAY_ID, CFG_SCH_PRIO_1);
}

static bool validChar(char c)
{
	return ((c >= '0') && (c <= '9')) || ((c >= 'A') && (c <= 'Z')) ||
			((c >= 'a') && (c <= 'z')) || (c == '-') || (c == '_') || (c == '/');
}

bool FS_ReplayMode_Set(const char *name, uint8_t len, uint16_t offset, uint8_t replay_flags)
{
	uint8_t i;

	if ((len == 0) || (len > FS_REPLAY_FOLDER_LEN) || (name[0] == '/')) return false;

	for (i = 0; i < len; ++i)
	{
		if (!validChar(name[i])) return false;
	}

	memcpy(folder, name, len);
	folder[len] = '\0';
	offset_s = offset;
	flags = replay_flags;
	return true;
}

const FS_ReplayMode_Status_t *FS_ReplayMode_GetStatus(void)
{
	return &status;
}

void FS_ReplayMode_Init(void)
{
	static const FS_AltRef_Zero_t noZero = { 0, 0, 0 };
	char path[FS_REPLAY_FOLDER_LEN + 16];

	memset(&status, 0, sizeof(status));
	status.state = FS_REPLAY_STATE_SEEKING;
	running = true;
	audio = false;
	timer = false;
	ticks = 0;
	hasNext = false;
	zeroDone = false;
	timeValid = false;
	baroPending = false;
	baroSeen = false;
	track.open = false;
	sensor.open = false;

	FS_LED_SetColour(FS_LED_GREEN);
	FS_LED_Pulse();

	/* Start with no ground level. Keep the zero of the real jump for later. */
	savedZero = *FS_AltRef_GetZero();
	FS_AltRef_RestoreZero(&noZero);
	FS_AltRef_Start();

	UTIL_SEQ_RegTask(1<<CFG_TASK_FS_REPLAY_ID, UTIL_SEQ_RFU, replayTask);

	fatfs = (FS_ResourceManager_RequestResource(FS_RESOURCE_FATFS)
			== FS_RESOURCE_MANAGER_SUCCESS);
	if (!fatfs)
	{
		finish(FS_REPLAY_STATE_ERROR, FS_REPLAY_ERROR_SD);
		return;
	}

	/* Read the configuration, with the same files as active mode */
	FS_Config_Init();
	FS_Config_Read("/config.txt");
	if (f_chdir("/config") == FR_OK)
	{
		FS_Config_Read(FS_State_Get()->config_filename);
	}
	f_chdir("/");

	if (folder[0] == '\0')
	{
		finish(FS_REPLAY_STATE_ERROR, FS_REPLAY_ERROR_NO_TRACK);
		return;
	}

	snprintf(path, sizeof(path), "/%s/track.csv", folder);
	if (!readerOpen(&track, path))
	{
		finish(FS_REPLAY_STATE_ERROR, FS_REPLAY_ERROR_OPEN);
		return;
	}

	snprintf(path, sizeof(path), "/%s/sensor.csv", folder);
	readerOpen(&sensor, path);

	readGNSS();
	if (!hasNext)
	{
		finish(FS_REPLAY_STATE_ERROR, FS_REPLAY_ERROR_NO_DATA);
		return;
	}
	startMs = nextMs;

	if (FS_Config_Get()->enable_audio && (FS_Audio_Init() == HAL_OK))
	{
		FS_AudioControl_Init();
		audio = true;
	}

	HW_TS_Create(CFG_TIM_PROC_ID_ISR, &timer_id, hw_ts_Repeated, replayTimer);
	HW_TS_Start(timer_id, TICK_TICKS);
	timer = true;

	UTIL_SEQ_SetTask(1<<CFG_TASK_FS_REPLAY_ID, CFG_SCH_PRIO_1);
}

void FS_ReplayMode_DeInit(void)
{
	running = false;

	if ((status.state == FS_REPLAY_STATE_SEEKING) ||
			(status.state == FS_REPLAY_STATE_PLAYING))
	{
		status.state = FS_REPLAY_STATE_STOPPED;
	}

	if (timer)
	{
		HW_TS_Stop(timer_id);
		HW_TS_Delete(timer_id);
		timer = false;
	}

	if (audio)
	{
		FS_AudioControl_DeInit();
		FS_Audio_DeInit();
		audio = false;
	}

	readerClose(&sensor);
	readerClose(&track);

	if (fatfs)
	{
		FS_ResourceManager_ReleaseResource(FS_RESOURCE_FATFS);
		fatfs = false;
	}

	/* The zero of the real jump */
	FS_AltRef_RestoreZero(&savedZero);
	FS_AltRef_Start();

	FS_LED_Off();
}
