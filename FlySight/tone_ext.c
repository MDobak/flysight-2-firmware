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


#include "tone_ext.h"

#define CHANGE_WINDOW_MSEC  1000  /* Time of the samples for the slope */
#define CHANGE_MAX_SAMPLES  32    /* 1 s at Rate 40 is 26 samples */
#define CHANGE_MIN_SAMPLES  3

static int32_t  values[CHANGE_MAX_SAMPLES];
static uint32_t times[CHANGE_MAX_SAMPLES];
static uint32_t first;   /* Index of the oldest sample */
static uint32_t count;
static uint32_t now;     /* Time of the last sample (ms) */

void FS_ToneExt_Reset(void)
{
	first = 0;
	count = 0;
	now = 0;
}

int32_t FS_ToneExt_Change(int32_t value, uint16_t rate)
{
	uint32_t i;

	if (value == FS_TONE_EXT_INVALID)
	{
		count = 0;
		return FS_TONE_EXT_INVALID;
	}

	now += rate;

	/* Remove old samples. If the buffer is full, remove the oldest. */
	while ((count > 0) &&
			((now - times[first] > CHANGE_WINDOW_MSEC) || (count >= CHANGE_MAX_SAMPLES)))
	{
		first = (first + 1) % CHANGE_MAX_SAMPLES;
		--count;
	}

	i = (first + count) % CHANGE_MAX_SAMPLES;
	values[i] = value;
	times[i] = now;
	++count;

	if (count < CHANGE_MIN_SAMPLES) return FS_TONE_EXT_INVALID;

	/* Least-squares slope. Times are relative to the last sample (s). Values
	   are relative to the last sample, for the float precision. */
	float st = 0, sv = 0, stt = 0, stv = 0;
	for (i = 0; i < count; ++i)
	{
		const uint32_t k = (first + i) % CHANGE_MAX_SAMPLES;
		const float t = -(float) (now - times[k]) / 1000.0f;
		const float v = (float) (values[k] - value);
		st += t;
		sv += v;
		stt += t * t;
		stv += t * v;
	}

	const float d = count * stt - st * st;
	if (d <= 0) return FS_TONE_EXT_INVALID;

	const float slope = (count * stv - st * sv) / d;
	if (slope >  2e9f) return  2000000000;
	if (slope < -2e9f) return -2000000000;
	return (int32_t) slope;
}
