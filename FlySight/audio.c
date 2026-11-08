/***************************************************************************
**                                                                        **
**  FlySight 2 firmware                                                   **
**  Copyright 2023 Bionic Avionics Inc.                                   **
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

#include <stdbool.h>

#include "main.h"
#include "app_common.h"
#include "audio.h"
#include "ff.h"
#include "log.h"
#include "stm32_seq.h"

#define PLLSAI1_TIMEOUT_VALUE (2U) /* 2 ms */

#define MAX9850_ADDR 0x20

#define MAX9850_REG_STATUS_A      0x00
#define MAX9850_REG_STATUS_B      0x01
#define MAX9850_REG_VOLUME        0x02
#define MAX9850_REG_GENERAL       0x03
#define MAX9850_REG_INTERRUPT     0x04
#define MAX9850_REG_ENABLE        0x05
#define MAX9850_REG_CLOCK         0x06
#define MAX9850_REG_CHARGE_PUMP   0x07
#define MAX9850_REG_LRCLK_MSB     0x08
#define MAX9850_REG_LRCLK_LSB     0x09
#define MAX9850_REG_DIGITAL_AUDIO 0x0a
#define MAX9850_REG_RESERVED      0x0b

#define AUDIO_SAMPLE_RATE      24000
#define AUDIO_INDEX_BITS       7
#define AUDIO_INTERPOLATE_BITS 8

#define AUDIO_FRAME_LEN        2048

#define AUDIO_UPDATE_MSEC 40
#define AUDIO_UPDATE_RATE (AUDIO_UPDATE_MSEC*1000/CFG_TS_TICK_VAL)

// Fork: a continuous tone moves to a new frequency with this time
// constant (2^9 samples, 21 ms)
#define AUDIO_GLIDE_SHIFT   9

// Fork: each tone starts and ends with a fade (5 ms). Without the fade, the
// signal jumps at the start and at the end, and the speaker clicks.
#define AUDIO_FADE_LEN      120
#define AUDIO_CONTINUOUS    UINT32_MAX

// Fork: FS_Audio_Splice changes the sound in the buffer. It starts this
// number of samples (4 ms) after the DMA read position.
#define AUDIO_SPLICE_MARGIN 96
#define AUDIO_SNAP_LEN      32
#define AUDIO_SNAP_COUNT    (AUDIO_FRAME_LEN / AUDIO_SNAP_LEN)

#define AUDIO_OP_TIMEOUT    100
#define AUDIO_RETRY_TIMEOUT 1000
#define AUDIO_DRAIN_TIMEOUT 2

static const int16_t sineTable[] =
{
		 0,   1607,   3211,   4807,   6392,   7961,   9511,  11038,
	 12539,  14009,  15446,  16845,  18204,  19519,  20787,  22004,
	 23169,  24278,  25329,  26318,  27244,  28105,  28897,  29621,
	 30272,  30851,  31356,  31785,  32137,  32412,  32609,  32727,
	 32767,  32727,  32609,  32412,  32137,  31785,  31356,  30851,
	 30272,  29621,  28897,  28105,  27244,  26318,  25329,  24278,
	 23169,  22004,  20787,  19519,  18204,  16845,  15446,  14009,
	 12539,  11038,   9511,   7961,   6392,   4807,   3211,   1607,
		 0,  -1608,  -3212,  -4808,  -6393,  -7962,  -9512, -11039,
	-12540, -14010, -15447, -16846, -18205, -19520, -20788, -22005,
	-23170, -24279, -25330, -26319, -27245, -28106, -28898, -29622,
	-30273, -30852, -31357, -31786, -32138, -32413, -32610, -32728,
	-32767, -32728, -32610, -32413, -32138, -31786, -31357, -30852,
	-30273, -29622, -28898, -28106, -27245, -26319, -25330, -24279,
	-23170, -22005, -20788, -19520, -18205, -16846, -15447, -14010,
	-12540, -11039,  -9512,  -7962,  -6393,  -4808,  -3212,  -1608
};

static uint32_t audioLen = 0;

// Fork: tone generator. A voice is one tone. remain is AUDIO_CONTINUOUS for
// the continuous tone (see FS_Audio_Tone).
typedef struct
{
	uint32_t phase;
	uint32_t step;
	uint32_t chirp;
	uint32_t age;     // Samples since the start (fade in)
	uint32_t remain;  // Samples to the end (fade out)
} FS_Audio_Voice_t;

static FS_Audio_Voice_t voice;
static FS_Audio_Voice_t nextVoice;    // Starts when voice ends
static bool hasNextVoice;
static FS_Audio_Voice_t voiceSnaps[AUDIO_SNAP_COUNT];  // voice at each AUDIO_SNAP_LEN samples
static uint32_t soundEnd;             // Buffer position of the end of the last tone
static volatile uint32_t audioTarget;

static int16_t audioBuffer[AUDIO_FRAME_LEN];

static volatile uint32_t frameCount;
static volatile uint32_t lastFrame;

static uint32_t readPos;
static uint32_t writePos;

static FIL audioFile;

static char audioList[AUDIO_LIST_LEN];
static char *audioListPtr;

static uint8_t audioVolume;

typedef enum
{
	AUDIO_IDLE,
	AUDIO_PLAY_TONE,
	AUDIO_PLAY_FILE,
	AUDIO_PLAY_LIST,
} FS_Audio_State_t;
static FS_Audio_State_t audioState = AUDIO_IDLE;

static uint8_t timer_id;

static uint32_t updateCount;
static uint32_t updateTotalTime;
static uint32_t updateMaxTime;
static uint32_t updateLastCall;
static uint32_t updateMaxInterval;
static uint32_t bufferUsed;

extern I2C_HandleTypeDef hi2c1;
extern SAI_HandleTypeDef hsai_BlockA1;

void FS_Audio_Stop(void);
static void FS_Audio_Idle(void);
static void FS_Audio_Timer(void);
static void FS_Audio_Update(void);

void HAL_SAI_TxCpltCallback(SAI_HandleTypeDef *hsai)
{
	if ((++frameCount) != lastFrame)
	{
		// Begin DMA transfer
		HAL_SAI_Transmit_DMA(hsai, (uint8_t*) audioBuffer, AUDIO_FRAME_LEN);
	}
	else
	{
		// Stop audio update timer
		HW_TS_Stop(timer_id);

		// Call update task
		UTIL_SEQ_SetTask(1<<CFG_TASK_FS_AUDIO_UPDATE_ID, CFG_SCH_PRIO_0);
	}
}

static HAL_StatusTypeDef FS_Audio_SetVolume(uint8_t volume)
{
	uint8_t buf[1];
	HAL_StatusTypeDef result;
	uint32_t timeout;

	if (audioVolume != volume)
	{
		/* Set volume */
		timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
		do
		{
			buf[0] = volume;
			result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_VOLUME, 1, buf, 1, AUDIO_OP_TIMEOUT);
			if (HAL_GetTick() > timeout)
			{
				FS_Log_WriteEvent("Couldn't set audio volume");
				return HAL_ERROR;
			}
		}
		while (result != HAL_OK);

		audioVolume = volume;
	}

	return HAL_OK;
}

HAL_StatusTypeDef FS_Audio_Init(void)
{
	uint8_t buf[1];
	HAL_StatusTypeDef result;
	uint32_t timeout;

	// Reset state
	updateCount = 0;
	updateTotalTime = 0;
	updateMaxTime = 0;
	updateMaxInterval = 0;
	bufferUsed = 0;

	/* Initialize I2C1 */
	MX_I2C1_Init();

	/* Mute audio outputs */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = (0x3f << 0);
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_VOLUME, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	audioVolume = 0x3f;

	/* Set stereo mode */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = 0;
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_GENERAL, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Set IC = 0x0 (SF = 1) */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = (0x0 << 2);
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_CLOCK, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Set CP = 0x09 */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = (0x09 << 0);
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_CHARGE_PUMP, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Set INT = 1 */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = (1 << 7);
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_LRCLK_MSB, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Set LSB = 32 */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = 32;
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_LRCLK_LSB, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Set slave mode, I2S, 16 bits */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = (0 << 7) | (0 << 6) | (0 << 5) | (0 << 4) | (1 << 3) | (0 << 2) | (0 << 0);
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_DIGITAL_AUDIO, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Enable MCLK, charge pump, headphone output and DAC */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = (1 << 7) | (1 << 6) | (1 << 5) | (1 << 4) | (1 << 3) | (1 << 2) | (1 << 0);
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_ENABLE, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}
	while (result != HAL_OK);

	/* Enable PLLSAI1 */
	__HAL_RCC_PLLSAI1_ENABLE();

	/* Wait until PLLSAI1 is ready */
	timeout = HAL_GetTick() + PLLSAI1_TIMEOUT_VALUE;
	while (LL_RCC_PLLSAI1_IsReady() != 1U)
	{
		if (HAL_GetTick() > timeout)
		{
			return HAL_ERROR;
		}
	}

	/* Initialize SAI1 */
	MX_SAI1_Init();

	/* Enable SAI to generate clock used by audio driver */
	__HAL_SAI_ENABLE(&hsai_BlockA1);

	// Initialize audio task
	UTIL_SEQ_RegTask(1<<CFG_TASK_FS_AUDIO_UPDATE_ID, UTIL_SEQ_RFU, FS_Audio_Update);

	// Initialize audio update timer
	HW_TS_Create(CFG_TIM_PROC_ID_ISR, &timer_id, hw_ts_Repeated, FS_Audio_Timer);

	return HAL_OK;
}

void FS_Audio_DeInit(void)
{
	uint8_t buf[1];
	HAL_StatusTypeDef result;
	uint32_t timeout;

	// Stop audio output
	FS_Audio_Stop();

	/* Disable MCLK, charge pump, headphone output and DAC */
	timeout = HAL_GetTick() + AUDIO_RETRY_TIMEOUT;
	do
	{
		buf[0] = 0;
		result = HAL_I2C_Mem_Write(&hi2c1, MAX9850_ADDR, MAX9850_REG_ENABLE, 1, buf, 1, AUDIO_OP_TIMEOUT);
		if (HAL_GetTick() > timeout)
		{
			FS_Log_WriteEvent("Couldn't disable audio DAC");
			break;
		}
	}
	while (result != HAL_OK);

	/* Disable I2C1 */
	if (HAL_I2C_DeInit(&hi2c1) != HAL_OK)
	{
		FS_Log_WriteEvent("Couldn't de-initialize I2C1");
	}

	/* Disable SAI1 */
	if (HAL_SAI_DeInit(&hsai_BlockA1) != HAL_OK)
	{
		FS_Log_WriteEvent("Couldn't de-initialize SAI1");
	}

	// Delete audio update timer
	HW_TS_Delete(timer_id);

	// Add event log entries for timing info
	FS_Log_WriteEvent("----------");
	FS_Log_WriteEvent("%lu/%lu slots used in audio buffer", bufferUsed, AUDIO_FRAME_LEN);
	FS_Log_WriteEvent("%lu ms average time spent in audio update task",
			(updateCount > 0) ? (updateTotalTime / updateCount) : 0);
	FS_Log_WriteEvent("%lu ms maximum time spent in audio update task", updateMaxTime);
	FS_Log_WriteEvent("%lu ms maximum time between calls to audio update task", updateMaxInterval);
}

static void FS_Audio_LoadTone(void)
{
	uint32_t size, i;

	// Fork: local copy of the voice, for speed
	FS_Audio_Voice_t v = voice;
	const uint32_t target = audioTarget;
	uint32_t pos = writePos;

	size = MIN(readPos + AUDIO_FRAME_LEN - writePos, audioLen);
	size = MIN(AUDIO_FRAME_LEN, size);

	for (i = 0; i < size; ++i, ++pos)
	{
		int32_t val = 0;

		// Fork: keep the voice for FS_Audio_Splice
		if (pos % AUDIO_SNAP_LEN == 0)
		{
			voiceSnaps[(pos / AUDIO_SNAP_LEN) % AUDIO_SNAP_COUNT] = v;
		}

		if ((v.remain == 0) && hasNextVoice)
		{
			v = nextVoice;
			hasNextVoice = false;
		}

		if (v.remain != 0)
		{
			// Get sample value
			const uint32_t index = v.phase >> (32 - AUDIO_INDEX_BITS);
			const uint32_t a1 = (v.phase << AUDIO_INDEX_BITS) >> (32 - AUDIO_INTERPOLATE_BITS);
			const uint32_t a2 = (1 << AUDIO_INTERPOLATE_BITS) - a1;
			const int16_t val1 = sineTable[index];
			const int16_t val2 = sineTable[(index + 1) % (1 << AUDIO_INDEX_BITS)];
			val = (val1 * (int32_t) a2 + val2 * (int32_t) a1) / (1 << AUDIO_INTERPOLATE_BITS);

			// Fork: fade in and fade out
			const uint32_t gain = MIN(v.age, v.remain);
			if (gain < AUDIO_FADE_LEN)
			{
				val = val * (int32_t) gain / AUDIO_FADE_LEN;
			}

			v.phase += v.step;
			v.step += v.chirp;
			if (v.age < AUDIO_FADE_LEN) ++v.age;

			if (v.remain == AUDIO_CONTINUOUS)
			{
				// Fork: a continuous tone moves smoothly to the new frequency
				v.step += (int32_t) (target - v.step) / (1 << AUDIO_GLIDE_SHIFT);
			}
			else
			{
				--v.remain;
			}
		}

		// Copy sample into audioBuffer
		audioBuffer[pos % AUDIO_FRAME_LEN] = (int16_t) val;
	}

	voice = v;
	writePos = pos;

	// Fork: a continuous tone has no end
	if (audioLen != AUDIO_CONTINUOUS)
	{
		audioLen -= size;
	}
}

static void FS_Audio_LoadFile(void)
{
	uint32_t size, s1, s2;
	UINT br;

	size = MIN(readPos + AUDIO_FRAME_LEN - writePos, audioLen);
	size = MIN(AUDIO_FRAME_LEN, size);

	s1 = MIN(AUDIO_FRAME_LEN - (writePos % AUDIO_FRAME_LEN), size);
	s2 = size - s1;

	// Read a block of data
	f_read(&audioFile, &audioBuffer[writePos % AUDIO_FRAME_LEN], s1 * sizeof(audioBuffer[0]), &br);
	f_read(&audioFile, &audioBuffer[0], s2 * sizeof(audioBuffer[0]), &br);

	writePos += size;
	audioLen -= size;
}

static void FS_Audio_Load(void)
{
	switch (audioState)
	{
	case AUDIO_IDLE:
		break;
	case AUDIO_PLAY_TONE:
		FS_Audio_LoadTone();
		break;
	case AUDIO_PLAY_FILE:
	case AUDIO_PLAY_LIST:
		FS_Audio_LoadFile();
		break;
	}
}

static void FS_Audio_InitBuffer(
		uint32_t bytes)
{
	// Initialize state
	frameCount = 0;
	lastFrame = (bytes + AUDIO_FRAME_LEN - 1) / AUDIO_FRAME_LEN;
	readPos = writePos = lastFrame * AUDIO_FRAME_LEN - bytes;

	// Initialize buffer
	FS_Audio_Load();
}

static void FS_Audio_InitTransfer(
		uint32_t bytes)
{
	// Initialize audio buffer
	FS_Audio_InitBuffer(bytes);

	// Begin DMA transfer
	HAL_SAI_Transmit_DMA(&hsai_BlockA1, (uint8_t*) (audioBuffer + readPos), AUDIO_FRAME_LEN - readPos);
}

// Fork: the read position of the DMA in the buffer. Returns false if the
// last frame is complete.
static bool FS_Audio_ReadPos(uint32_t *pos)
{
	uint32_t primask_bit, count, cndtr;

	primask_bit = __get_PRIMASK();
	__disable_irq();
	count = frameCount;
	cndtr = hsai_BlockA1.hdmatx->Instance->CNDTR;
	__set_PRIMASK(primask_bit);

	if (count == lastFrame) return false;
	*pos = (count + 1) * AUDIO_FRAME_LEN - cndtr;
	return true;
}

// Fork: change the tone while the DMA continues. The current voice fades out
// AUDIO_SPLICE_MARGIN samples after the read position. Then next starts. If
// next is NULL, the audio stops after the fade. The DMA does not stop, thus
// the audio clock continues and the speaker does not click. Returns false if
// the audio does not play a tone.
static bool FS_Audio_Splice(const FS_Audio_Voice_t *next)
{
	uint32_t primask_bit, read, pos, end, last;

	if (audioState != AUDIO_PLAY_TONE) return false;

	if (!FS_Audio_ReadPos(&read))
	{
		FS_Audio_Idle();
		return false;
	}

	// Go back to the voice of a position in the buffer
	pos = read + AUDIO_SPLICE_MARGIN;
	pos += (AUDIO_SNAP_LEN - pos % AUDIO_SNAP_LEN) % AUDIO_SNAP_LEN;
	if (pos < writePos)
	{
		voice = voiceSnaps[(pos / AUDIO_SNAP_LEN) % AUDIO_SNAP_COUNT];
		writePos = pos;
	}

	// Fade out the current voice
	if (voice.remain > AUDIO_FADE_LEN)
	{
		voice.remain = AUDIO_FADE_LEN;
	}
	end = writePos + voice.remain;

	hasNextVoice = (next != NULL);
	if (hasNextVoice)
	{
		nextVoice = *next;
		end = (next->remain == AUDIO_CONTINUOUS) ? AUDIO_CONTINUOUS : end + next->remain;
	}
	soundEnd = end;

	// The last frame ends with silence after the sound
	if (end == AUDIO_CONTINUOUS)
	{
		last = UINT32_MAX;
		audioLen = AUDIO_CONTINUOUS;
	}
	else
	{
		last = (end + AUDIO_FRAME_LEN - 1) / AUDIO_FRAME_LEN;
		audioLen = last * AUDIO_FRAME_LEN - writePos;
	}

	primask_bit = __get_PRIMASK();
	__disable_irq();
	const bool done = (frameCount == lastFrame);
	if (!done) lastFrame = MAX(last, frameCount + 1);
	__set_PRIMASK(primask_bit);

	if (done)
	{
		FS_Audio_Idle();
		return false;
	}

	// Write the new samples before the DMA reads them
	readPos = read;
	FS_Audio_Load();

	return true;
}

static void FS_Audio_MakeVoice(
		FS_Audio_Voice_t *v,
		uint32_t startFrequency,
		uint32_t endFrequency,
		uint32_t len)
{
	v->phase = 0;
	v->step = ((uint64_t) startFrequency << 32) / AUDIO_SAMPLE_RATE;
	v->age = 0;
	v->remain = len;

	// Calculate chirp
	const uint32_t end_step = ((uint64_t) endFrequency << 32) / AUDIO_SAMPLE_RATE;
	if (len == AUDIO_CONTINUOUS) v->chirp = 0;
	else if (end_step > v->step) v->chirp = (end_step - v->step) / len;
	else                         v->chirp = 0 - (v->step - end_step) / len;
}

void FS_Audio_Beep(
		uint32_t startFrequency,
		uint32_t endFrequency,
		uint32_t duration,
		uint8_t volume)
{
	FS_Audio_Voice_t v;
	const uint32_t len = (duration * AUDIO_SAMPLE_RATE) / 1000;

	if (len == 0) return;
	FS_Audio_MakeVoice(&v, startFrequency, endFrequency, len);

	// Fork: during a tone, change the tone with no stop of the DMA
	if (FS_Audio_Splice(&v))
	{
		FS_Audio_SetVolume(volume);
		return;
	}

	if (audioState != AUDIO_IDLE)
	{
		FS_Audio_Stop();
	}

	audioState = AUDIO_PLAY_TONE;

	// Set volume
	FS_Audio_SetVolume(volume);

	// Remember frequency and duration
	voice = v;
	hasNextVoice = false;
	audioLen = len;

	// Initialize audio buffer
	FS_Audio_InitTransfer(audioLen);
	soundEnd = lastFrame * AUDIO_FRAME_LEN;

	// Reset last update call time
	updateLastCall = 0;

	// Start audio update timer
	HW_TS_Start(timer_id, AUDIO_UPDATE_RATE);
}

static void FS_Audio_SyncTxCplt(DMA_HandleTypeDef *hdma)
{
	SAI_HandleTypeDef *hsai = (SAI_HandleTypeDef *) hdma->Parent;

	// Finish the frame started by FS_Audio_BeepSync the way the HAL would
	hsai->XferCount = 0;
	hsai->Instance->CR1 &= ~SAI_xCR1_DMAEN;
	hsai->State = HAL_SAI_STATE_READY;

	// Remaining frames use the normal path
	HAL_SAI_TxCpltCallback(hsai);
}

bool FS_Audio_BeepSync(
		uint32_t startFrequency,
		uint32_t endFrequency,
		uint32_t duration,
		uint8_t volume,
		void (*onStart)(void))
{
	SAI_HandleTypeDef *hsai = &hsai_BlockA1;
	uint32_t primask_bit;
	uint32_t timeout;
	FS_Audio_Voice_t v;
	const uint32_t len = (duration * AUDIO_SAMPLE_RATE) / 1000;

	// Everything that takes a variable amount of time is done first, so the
	// delay between onStart and the first sample of the tone is constant

	if (hsai->State == HAL_SAI_STATE_RESET)
	{
		// Audio is not initialized
		return false;
	}

	if (len == 0)
	{
		return false;
	}
	FS_Audio_MakeVoice(&v, startFrequency, endFrequency, len);

	if (audioState != AUDIO_IDLE)
	{
		/* Enter critical section */
		primask_bit = __get_PRIMASK();
		__disable_irq();

		// Stop DMA transfer, but unlike FS_Audio_Stop leave SAI enabled so
		// the DAC keeps its clocks
		hsai->Instance->CR1 &= ~SAI_xCR1_DMAEN;
		HAL_DMA_Abort(hsai->hdmatx);
		hsai->State = HAL_SAI_STATE_READY;

		// Stop audio update timer
		HW_TS_Stop(timer_id);

		/* Exit critical section */
		__set_PRIMASK(primask_bit);

		// Go to idle state
		FS_Audio_Idle();
	}

	if (hsai->State != HAL_SAI_STATE_READY)
	{
		return false;
	}

	audioState = AUDIO_PLAY_TONE;

	// Make sure SAI is generating clocks
	if ((hsai->Instance->CR1 & SAI_xCR1_SAIEN) == 0U)
	{
		__HAL_SAI_ENABLE(hsai);
	}

	// Set volume
	FS_Audio_SetVolume(volume);

	// Remember frequency and duration. The voice starts at a zero crossing.
	voice = v;
	hasNextVoice = false;
	audioLen = len;

	// Initialize audio buffer
	FS_Audio_InitBuffer(audioLen);
	soundEnd = lastFrame * AUDIO_FRAME_LEN;

	// Arm DMA for the first frame. This is HAL_SAI_Transmit_DMA without its
	// final step, so nothing is sent until SAI is allowed to request data.
	hsai->pBuffPtr = (uint8_t*) (audioBuffer + readPos);
	hsai->XferSize = AUDIO_FRAME_LEN - readPos;
	hsai->XferCount = AUDIO_FRAME_LEN - readPos;
	hsai->ErrorCode = HAL_SAI_ERROR_NONE;
	hsai->State = HAL_SAI_STATE_BUSY_TX;

	hsai->hdmatx->XferHalfCpltCallback = NULL;
	hsai->hdmatx->XferCpltCallback = FS_Audio_SyncTxCplt;
	hsai->hdmatx->XferErrorCallback = NULL;
	hsai->hdmatx->XferAbortCallback = NULL;

	if (HAL_DMA_Start_IT(hsai->hdmatx, (uint32_t) hsai->pBuffPtr,
			(uint32_t) &hsai->Instance->DR, hsai->XferSize) != HAL_OK)
	{
		hsai->State = HAL_SAI_STATE_READY;
		audioState = AUDIO_IDLE;
		return false;
	}

	// Wait for samples left over from interrupted audio to be sent
	timeout = HAL_GetTick() + AUDIO_DRAIN_TIMEOUT;
	while ((hsai->Instance->SR & SAI_xSR_FLVL) != SAI_FIFOSTATUS_EMPTY)
	{
		if (HAL_GetTick() > timeout)
		{
			break;
		}
	}

	/* Enter critical section */
	primask_bit = __get_PRIMASK();
	__disable_irq();

	// Mark start of tone
	if (onStart)
	{
		onStart();
	}

	// Begin DMA transfer
	hsai->Instance->CR1 |= SAI_xCR1_DMAEN;

	/* Exit critical section */
	__set_PRIMASK(primask_bit);

	// Reset last update call time
	updateLastCall = 0;

	// Start audio update timer
	HW_TS_Start(timer_id, AUDIO_UPDATE_RATE);

	return true;
}

// Fork: a tone with no end, for Flatline. FS_Audio_SetToneFrequency
// changes the frequency. FS_Audio_EndTone ends it with a fade.
void FS_Audio_Tone(
		uint32_t frequency,
		uint8_t volume)
{
	FS_Audio_Voice_t v;

	FS_Audio_MakeVoice(&v, frequency, frequency, AUDIO_CONTINUOUS);
	audioTarget = v.step;

	if (FS_Audio_Splice(&v))
	{
		FS_Audio_SetVolume(volume);
		return;
	}

	if (audioState != AUDIO_IDLE)
	{
		FS_Audio_Stop();
	}

	audioState = AUDIO_PLAY_TONE;

	// Set volume
	FS_Audio_SetVolume(volume);

	voice = v;
	hasNextVoice = false;
	audioLen = AUDIO_CONTINUOUS;
	soundEnd = AUDIO_CONTINUOUS;

	// Initialize audio buffer. The DMA restarts until the tone ends.
	frameCount = 0;
	lastFrame = UINT32_MAX;
	readPos = writePos = 0;
	FS_Audio_Load();
	HAL_SAI_Transmit_DMA(&hsai_BlockA1, (uint8_t*) audioBuffer, AUDIO_FRAME_LEN);

	// Reset last update call time
	updateLastCall = 0;

	// Start audio update timer
	HW_TS_Start(timer_id, AUDIO_UPDATE_RATE);
}

void FS_Audio_SetToneFrequency(
		uint32_t frequency)
{
	audioTarget = ((uint64_t) frequency << 32) / AUDIO_SAMPLE_RATE;
}

// Fork: end the continuous tone with a fade
void FS_Audio_EndTone(void)
{
	if (FS_Audio_IsTone())
	{
		FS_Audio_Splice(NULL);
	}
}

// Fork: stop the audio. A tone ends with a fade. Other sounds stop at once.
void FS_Audio_FadeOut(void)
{
	if (!FS_Audio_Splice(NULL) && (audioState != AUDIO_IDLE))
	{
		FS_Audio_Stop();
	}
}

bool FS_Audio_IsTone(void)
{
	return (audioState == AUDIO_PLAY_TONE) &&
			((voice.remain == AUDIO_CONTINUOUS) ||
			 (hasNextVoice && (nextVoice.remain == AUDIO_CONTINUOUS)));
}

// Fork: true if the audio is idle, or if only the silence at the end of a
// tone is left. A new tone can then start with FS_Audio_Splice.
bool FS_Audio_IsQuiet(void)
{
	uint32_t pos;

	if (audioState == AUDIO_IDLE) return true;
	if ((audioState != AUDIO_PLAY_TONE) || (soundEnd == AUDIO_CONTINUOUS)) return false;
	if (!FS_Audio_ReadPos(&pos)) return true;
	return pos >= soundEnd;
}

static bool FS_Audio_PlayFile(
		const char *filename)
{
	f_chdir("/audio");

	if (f_open(&audioFile, filename, FA_READ) != FR_OK)
		return false;

	// TODO: Change this to parse WAV file properly
	f_lseek(&audioFile, 0x28);

	// Remember duration
	UINT br;
	uint32_t len;
	f_read(&audioFile, &len, 4, &br);
	audioLen = len / sizeof(audioBuffer[0]);

	// TODO: Change this to parse WAV file properly
	f_lseek(&audioFile, 0x2c);

	// Initialize audio buffer
	FS_Audio_InitTransfer(audioLen);

	// Reset last update call time
	updateLastCall = 0;

	// Start audio update timer
	HW_TS_Start(timer_id, AUDIO_UPDATE_RATE);

	return true;
}

void FS_Audio_Play(
		const char *filename,
		uint8_t volume)
{
	if (audioState != AUDIO_IDLE)
	{
		FS_Audio_Stop();
	}

	audioState = AUDIO_PLAY_FILE;

	// Set volume
	FS_Audio_SetVolume(volume);

	if (!FS_Audio_PlayFile(filename))
	{
		audioState = AUDIO_IDLE;
	}
}

static bool FS_Audio_LoadList(void)
{
	char filename[20];
	char ch;

	while ((ch = *(audioListPtr++)))
	{
		if ('0' <= ch && ch <= '9')
		{
			sprintf(filename, "%c.wav", ch);
			if (FS_Audio_PlayFile(filename))
				return true;
		}
		else if (ch == '-')
		{
			if (FS_Audio_PlayFile("minus.wav"))
				return true;
		}
		else if (ch == '.')
		{
			if (FS_Audio_PlayFile("dot.wav"))
				return true;
		}
	}
	return false;
}

void FS_Audio_PlayList(
		const char *list,
		uint8_t volume)
{
	if (audioState != AUDIO_IDLE)
	{
		FS_Audio_Stop();
	}

	audioState = AUDIO_PLAY_LIST;

	// Keep a copy of the list
	strncpy(audioList, list, sizeof(audioList));
	audioListPtr = audioList;

	// Set volume
	FS_Audio_SetVolume(volume);

	// Initialize audio buffer
	if (!FS_Audio_LoadList())
	{
		audioState = AUDIO_IDLE;
	}
}

static void FS_Audio_Idle(void)
{
	switch (audioState) {
	case AUDIO_IDLE:
		return;
	case AUDIO_PLAY_FILE:
	case AUDIO_PLAY_LIST:
		f_close(&audioFile);
		break;
	default:
		break;
	}

	audioState = AUDIO_IDLE;
	hasNextVoice = false;
}

bool FS_Audio_IsIdle(void)
{
	return audioState == AUDIO_IDLE;
}

void FS_Audio_Stop(void)
{
	uint32_t primask_bit;

	/* Enter critical section */
	primask_bit = __get_PRIMASK();
	__disable_irq();

	// Stop DMA transfer
	HAL_SAI_DMAStop(&hsai_BlockA1);

	// Stop audio update timer
	HW_TS_Stop(timer_id);

	/* Exit critical section */
	__set_PRIMASK(primask_bit);

	// Go to idle state
	FS_Audio_Idle();
}

static void FS_Audio_Timer(void)
{
	// Call update task
	UTIL_SEQ_SetTask(1<<CFG_TASK_FS_AUDIO_UPDATE_ID, CFG_SCH_PRIO_0);
}

static void FS_Audio_Update(void)
{
	uint32_t msStart, msEnd;
	uint32_t primask_bit;
	uint32_t cndtr, count;

	if (audioState == AUDIO_IDLE) return;

	msStart = HAL_GetTick();

	if (updateLastCall != 0)
	{
		updateMaxInterval = MAX(updateMaxInterval, msStart - updateLastCall);
	}
	updateLastCall = msStart;

	/* Enter critical section */
	primask_bit = __get_PRIMASK();
	__disable_irq();

	count = frameCount;
	cndtr = hsai_BlockA1.hdmatx->Instance->CNDTR;

	/* Exit critical section */
	__set_PRIMASK(primask_bit);

	if (count == lastFrame)
	{
		uint32_t prevState = audioState;

		// Last frame complete
		FS_Audio_Idle();

		if (prevState == AUDIO_PLAY_LIST)
		{
			audioState = AUDIO_PLAY_LIST;

			// Initialize audio buffer
			if (!FS_Audio_LoadList())
			{
				audioState = AUDIO_IDLE;
			}
		}
	}
	else
	{
		// Get read position
		readPos = (count + 1) * AUDIO_FRAME_LEN - cndtr;

		// Update buffer statistics
		if (writePos != lastFrame * AUDIO_FRAME_LEN)
		{
			if (writePos > readPos)
			{
				bufferUsed = MAX(bufferUsed, readPos + AUDIO_FRAME_LEN - writePos);
			}
			else
			{
				bufferUsed = AUDIO_FRAME_LEN;
			}
		}

		// Update buffer
		FS_Audio_Load();
	}

	++updateCount;

	msEnd = HAL_GetTick();
	updateTotalTime += msEnd - msStart;
	updateMaxTime = MAX(updateMaxTime, msEnd - msStart);
}
