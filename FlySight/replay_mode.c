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
#define TAIL_BYTES   32768  /* The landing search reads this much of the file end */
#define LANDING_MSEC 5000   /* The landing is the average of this time at the end */
#define MIN_RATE     40     /* Limits of Rate in config.txt (ms) */
#define MAX_RATE     1000

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

/* The audio expects one GNSS sample at each Rate interval. A track can have
 * more samples (for example 20 Hz). */
static uint16_t configRate;  /* Rate from the configuration (ms), or 0 */
static int64_t audioMs;      /* Time of the last sample for the audio, or -1 */
static int64_t lastMs;       /* Time of the last sample, or -1 */

/* Barometer */
#define TIME_LINES   32  /* $TIME lines for the time offset */
static int64_t timeOffset;   /* GPS time (ms) - sensor time (ms) */
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

/* Go to a position in the file. After a position that is not 0, skip the
   rest of the line. */
static bool readerSeek(Reader_t *r, FSIZE_t pos)
{
	char line[LINE_LEN];

	r->len = 0;
	r->pos = 0;
	r->eof = false;
	if (!r->open || (f_lseek(&r->file, pos) != FR_OK)) return false;
	if (pos > 0) readerLine(r, line, sizeof(line));
	return true;
}

static FSIZE_t tailStart(Reader_t *r)
{
	const FSIZE_t size = f_size(&r->file);
	return (size > TAIL_BYTES) ? size - TAIL_BYTES : 0;
}

/* Average GNSS altitude (mm) of the last LANDING_MSEC of the track, with a
   3D fix. Two passes over the end of the file: find the last time, then
   add the rows near it. */
static bool landingHeight(int32_t *hmsl)
{
	char line[LINE_LEN];
	FS_GNSS_Data_t data;
	int64_t ms, last = INT64_MIN, sum = 0;
	uint32_t count = 0;
	const FSIZE_t start = tailStart(&track);
	int pass;

	for (pass = 0; pass < 2; ++pass)
	{
		if (!readerSeek(&track, start)) return false;
		while (readerLine(&track, line, sizeof(line)))
		{
			if (!ReplayParse_GNSS(line, &data, &ms) || (data.gpsFix != 3)) continue;
			if (pass == 0)
			{
				if (ms > last) last = ms;
			}
			else if (ms >= last - LANDING_MSEC)
			{
				sum += data.hMSL;
				++count;
			}
		}
		if (last == INT64_MIN) return false;
	}

	if (count == 0) return false;
	*hmsl = (int32_t) (sum / count);
	return true;
}

/* Average pressure (Pa * 100) of the last LANDING_MSEC of sensor.csv. The
   sensor time is enough. A GPS time is not necessary. */
static bool landingPressure(int32_t *pressure)
{
	char line[LINE_LEN];
	int64_t t, last = INT64_MIN, sum = 0;
	int32_t p;
	uint32_t count = 0;
	const FSIZE_t start = sensor.open ? tailStart(&sensor) : 0;
	int pass;

	if (!sensor.open) return false;

	for (pass = 0; pass < 2; ++pass)
	{
		if (!readerSeek(&sensor, start)) return false;
		while (readerLine(&sensor, line, sizeof(line)))
		{
			if (!ReplayParse_Baro(line, &t, &p)) continue;
			if (pass == 0)
			{
				if (t > last) last = t;
			}
			else if (t >= last - (int64_t) LANDING_MSEC * 1000)
			{
				sum += p;
				++count;
			}
		}
		if (last == INT64_MIN) return false;
	}

	if (count == 0) return false;
	*pressure = (int32_t) (sum / count);
	return true;
}

/* Set the ground level from the end of the track (the landing). Then go
   back to the start of the files. */
static void setLandingZero(void)
{
	FS_AltRef_Zero_t landing = { 0, 0, 0 };

	if (landingHeight(&landing.ground_hmsl)) landing.flags |= FS_ALT_REF_GNSS;
	if (landingPressure(&landing.ground_pressure)) landing.flags |= FS_ALT_REF_BARO;
	if (landing.flags != 0) FS_AltRef_RestoreZero(&landing);

	readerSeek(&track, 0);
	readerSeek(&sensor, 0);
}

/* Find the offset from the sensor time to the GPS time. Each $TIME line
   gives an offset, but some lines are 1 s wrong (the time pulse and its
   UBX-TIM-TP message do not always agree). Use the most frequent offset of
   the first TIME_LINES lines. Then go back to the start of the file. */
static bool findTimeOffset(int64_t *offset)
{
	char line[LINE_LEN];
	int64_t offsets[TIME_LINES];
	int64_t t, g;
	uint32_t n = 0, i, j, best = 0, bestCount = 0;

	if (!sensor.open || !readerSeek(&sensor, 0)) return false;

	while ((n < TIME_LINES) && readerLine(&sensor, line, sizeof(line)))
	{
		if (ReplayParse_Time(line, &t, &g)) offsets[n++] = g - t / 1000;
	}
	readerSeek(&sensor, 0);
	if (n == 0) return false;

	for (i = 0; i < n; ++i)
	{
		uint32_t count = 0;
		for (j = 0; j < n; ++j)
		{
			if (offsets[j] == offsets[i]) ++count;
		}
		if (count > bestCount)
		{
			best = i;
			bestCount = count;
		}
	}

	*offset = offsets[best];
	return true;
}

/* Give the barometer measurements up to the GPS time until_ms to alt_ref.c */
static void feedBaro(int64_t until_ms)
{
	char line[LINE_LEN];
	int64_t t;
	int32_t p;

	for (;;)
	{
		if (!baroPending)
		{
			if (!readerLine(&sensor, line, sizeof(line))) return;

			if (!ReplayParse_Baro(line, &t, &p)) continue;

			baroUs = t;
			baroPressure = p;
			baroGps = t / 1000 + timeOffset;
			baroPending = true;
			baroSeen = true;
		}

		if (baroGps > until_ms) return;

		FS_AltRef_UpdateBaro(baroPressure, (uint64_t) baroUs);
		baroPending = false;
	}
}

/* Give the current sample to the audio at the Rate interval of the
 * configuration. Skip the samples between. The audio uses Rate as the time
 * between two samples (speech interval, dive angle). Thus set Rate to the
 * real time since the last sample for the audio. */
static void feedAudio(int64_t sampleMs)
{
	/* The configuration is not constant memory (config.c). Replay changes
	 * only Rate. The next mode reads the configuration again. */
	FS_Config_Data_t *config = (FS_Config_Data_t *) FS_Config_Get();
	const int64_t step = (lastMs >= 0) ? sampleMs - lastMs : configRate;
	int64_t since = configRate;

	if (audioMs >= 0)
	{
		since = sampleMs - audioMs;
		if (since + step / 2 < configRate) return;
	}

	if (since < MIN_RATE) since = MIN_RATE;
	if (since > MAX_RATE) since = MAX_RATE;
	config->rate = (uint16_t) since;
	audioMs = sampleMs;

	FS_AudioControl_UpdateGNSS(&current);
}

static void stepSample(bool play)
{
	const FS_Config_Data_t *config = FS_Config_Get();
	const int64_t sampleMs = nextMs;

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
		feedAudio(sampleMs);
	}
	lastMs = sampleMs;

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
	configRate = 0;
	audioMs = -1;
	lastMs = -1;
	baroPending = false;
	baroSeen = false;
	track.open = false;
	sensor.open = false;

	FS_LED_SetColour(FS_LED_GREEN);
	FS_LED_Pulse();

	/* Keep the zero of the real jump for later. To set the ground level from
	   the track, start with no ground level. Else use the ground level of the
	   FlySight. */
	savedZero = *FS_AltRef_GetZero();
	if (flags & (FS_REPLAY_FLAG_ZERO | FS_REPLAY_FLAG_LANDING))
	{
		FS_AltRef_RestoreZero(&noZero);
	}
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
	configRate = FS_Config_Get()->rate;

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
	/* Without a GPS time, the barometer data cannot be used */
	if (readerOpen(&sensor, path) && !findTimeOffset(&timeOffset))
	{
		readerClose(&sensor);
	}

	if (flags & FS_REPLAY_FLAG_LANDING)
	{
		setLandingZero();
	}

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

	if (configRate != 0)
	{
		((FS_Config_Data_t *) FS_Config_Get())->rate = configRate;
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
