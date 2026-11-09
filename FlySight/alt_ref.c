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

#include <math.h>
#include <stdlib.h>

#include "main.h"
#include "alt_ref.h"

#define STILL_MSEC        60000  /* Time with no movement before the automatic zero */
#define STILL_H_SPEED     50     /* Maximum ground speed (cm/s) */
#define STILL_V_SPEED     500    /* Maximum vertical speed (mm/s) */
#define STILL_S_ACC       1000   /* Maximum speed accuracy (mm/s) */
#define GOOD_V_ACC        10000  /* Maximum vertical accuracy for the GNSS zero (mm) */
#define MIN_SAMPLES       10     /* Minimum number of samples for a zero */
#define GNSS_ALPHA        0.1    /* Filter for the manual zero (about 2 s at 5 Hz) */
#define BARO_ALPHA        0.2
#define ITOW_WEEK         604800000UL
#define MAX_BARO_AGE      10     /* GNSS measurements with no barometer data */
#define VEL_ALPHA         0.3f   /* Filter for the barometer vertical speed */
#define ALT_TAU_MSEC      1000   /* Time constant of the barometer height filter */
#define ALT_MAX_STEP_MSEC 1000   /* A longer time between measurements starts the filter again */

static FS_AltRef_Zero_t zero = { 0, 0, 0 };
static uint32_t epoch;

/* Barometer measurements since the last GNSS measurement (interrupt) */
static volatile int64_t baroSum;
static volatile uint32_t baroCount;
static volatile uint64_t baroTime;

/* Vertical speed from the barometer */
static float prevBaroAlt;
static uint64_t prevBaroTime;
static bool hasPrevBaro;
static float baroVelD;

/* Barometer altitude (standard atmosphere, m). The air flow in freefall
   gives noise of about 10 m in each measurement. The filter moves the
   altitude with the vertical speed between two measurements, then a part
   of the distance to the new barometer value. Thus it has no delay. */
static float baroAlt;
static bool hasBaroAlt;
static uint32_t altITOW;

/* Latest values */
static int32_t pressure;
static uint8_t pressureAge = 255;

/* Filters for the manual zero */
static double filterH, filterP;
static uint32_t countH, countP;

/* Time with no movement */
static bool still;
static uint32_t stillStart;
static int64_t stillSumH, stillSumP;
static uint32_t stillCountH, stillCountP;

static float pressureAltitude(int32_t p)
{
	/* Standard atmosphere, p in Pa * 100, result in m */
	return 44330.77f * (1.0f - powf((float) p / 10132500.0f, 0.190263f));
}

void FS_AltRef_Start(void)
{
	uint32_t primask_bit = __get_PRIMASK();
	__disable_irq();
	baroSum = 0;
	baroCount = 0;
	__set_PRIMASK(primask_bit);

	pressureAge = 255;
	hasPrevBaro = false;
	baroVelD = 0;
	hasBaroAlt = false;
	countH = 0;
	countP = 0;
	still = false;
}

/* Update the barometer altitude filter at each GNSS measurement. newBaro is
   true if there is a new pressure. */
static void updateBaroAlt(const FS_GNSS_Data_t *data, bool newBaro)
{
	const uint32_t dt = (data->iTOW + ITOW_WEEK - altITOW) % ITOW_WEEK;
	const float raw = pressureAltitude(pressure);

	if (hasBaroAlt && (dt == 0)) return;
	altITOW = data->iTOW;

	if (!hasBaroAlt || (dt > ALT_MAX_STEP_MSEC))
	{
		/* Start again with the last pressure */
		if (newBaro || hasBaroAlt)
		{
			baroAlt = raw;
			hasBaroAlt = true;
		}
		return;
	}

	/* Vertical speed (m/s, down) from the GNSS, or from the barometer */
	const float velD = (data->gpsFix == 3) ? data->velD / 1000.0f : baroVelD;
	baroAlt -= velD * dt / 1000.0f;

	if (newBaro)
	{
		baroAlt += (raw - baroAlt) * dt / (float) (ALT_TAU_MSEC + dt);
	}
}

void FS_AltRef_UpdateBaro(int32_t p, uint64_t time)
{
	if (p <= 0) return;
	baroSum += p;
	++baroCount;
	baroTime = time;
}

void FS_AltRef_UpdateGNSS(const FS_GNSS_Data_t *data, bool auto_zero)
{
	int64_t sum;
	uint32_t count;
	uint64_t time;
	bool goodH, isStill;

	/* Average of the barometer since the last GNSS measurement */
	uint32_t primask_bit = __get_PRIMASK();
	__disable_irq();
	sum = baroSum;
	count = baroCount;
	time = baroTime;
	baroSum = 0;
	baroCount = 0;
	__set_PRIMASK(primask_bit);

	if (count > 0)
	{
		pressure = (int32_t) (sum / count);
		pressureAge = 0;

		/* Vertical speed from the change of the barometer altitude */
		const float alt = pressureAltitude(pressure);
		if (hasPrevBaro && (time > prevBaroTime))
		{
			const float v = (prevBaroAlt - alt) / ((float) (time - prevBaroTime) * 1e-6f);
			baroVelD += VEL_ALPHA * (v - baroVelD);
		}
		prevBaroAlt = alt;
		prevBaroTime = time;
		hasPrevBaro = true;
		filterP = (countP == 0) ? pressure : filterP + BARO_ALPHA * (pressure - filterP);
		++countP;
	}
	else if (pressureAge < 255)
	{
		++pressureAge;
		if (pressureAge > MAX_BARO_AGE)
		{
			hasPrevBaro = false;
			baroVelD = 0;
			hasBaroAlt = false;
		}
	}

	updateBaroAlt(data, count > 0);

	goodH = (data->gpsFix == 3) && (data->vAcc < GOOD_V_ACC);
	if (goodH)
	{
		filterH = (countH == 0) ? data->hMSL : filterH + GNSS_ALPHA * (data->hMSL - filterH);
		++countH;
	}
	else
	{
		countH = 0;
	}

	/* No movement */
	isStill = (data->gpsFix == 3) &&
			(data->gSpeed < STILL_H_SPEED) &&
			(labs(data->velD) < STILL_V_SPEED) &&
			(data->sAcc < STILL_S_ACC);

	if (!isStill)
	{
		still = false;
		return;
	}

	if (!still)
	{
		still = true;
		stillStart = data->iTOW;
		stillSumH = 0;
		stillSumP = 0;
		stillCountH = 0;
		stillCountP = 0;
	}

	if (goodH)
	{
		stillSumH += data->hMSL;
		++stillCountH;
	}

	if (count > 0)
	{
		stillSumP += pressure;
		++stillCountP;
	}

	if (auto_zero &&
			((data->iTOW + ITOW_WEEK - stillStart) % ITOW_WEEK >= STILL_MSEC))
	{
		/* The zero follows the average while the FlySight does not move */
		const FS_AltRef_Zero_t old = zero;

		if (stillCountH >= MIN_SAMPLES)
		{
			zero.ground_hmsl = (int32_t) (stillSumH / stillCountH);
			zero.flags |= FS_ALT_REF_GNSS | FS_ALT_REF_AUTO;
		}

		if (stillCountP >= MIN_SAMPLES)
		{
			zero.ground_pressure = (int32_t) (stillSumP / stillCountP);
			zero.flags |= FS_ALT_REF_BARO | FS_ALT_REF_AUTO;
		}

		if ((old.flags != zero.flags) ||
				(old.ground_hmsl != zero.ground_hmsl) ||
				(old.ground_pressure != zero.ground_pressure))
		{
			++epoch;
		}
	}
}

uint8_t FS_AltRef_SetZero(void)
{
	uint8_t set = 0;

	if (countH >= MIN_SAMPLES)
	{
		zero.ground_hmsl = (int32_t) filterH;
		set |= FS_ALT_REF_GNSS;
	}

	if (countP >= MIN_SAMPLES)
	{
		zero.ground_pressure = (int32_t) filterP;
		set |= FS_ALT_REF_BARO;
	}

	if (set)
	{
		zero.flags = (zero.flags | set) & ~FS_ALT_REF_AUTO;
		++epoch;

		/* Keep the manual zero for at least one more minute */
		still = false;
	}

	return set;
}

uint8_t FS_AltRef_SetZeroValues(uint8_t mask, int32_t ground_hmsl, int32_t ground_pressure)
{
	uint8_t set = 0;

	if (mask & FS_ALT_REF_GNSS)
	{
		zero.ground_hmsl = ground_hmsl;
		set |= FS_ALT_REF_GNSS;
	}

	if ((mask & FS_ALT_REF_BARO) && (ground_pressure > 0))
	{
		zero.ground_pressure = ground_pressure;
		set |= FS_ALT_REF_BARO;
	}

	if (set)
	{
		zero.flags = (zero.flags | set) & ~FS_ALT_REF_AUTO;
		++epoch;
		still = false;
	}

	return set;
}

const FS_AltRef_Zero_t *FS_AltRef_GetZero(void)
{
	return &zero;
}

bool FS_AltRef_UsesBaro(uint8_t source)
{
	return (source == 1) && (pressureAge <= MAX_BARO_AGE) && (zero.flags & FS_ALT_REF_BARO);
}

int32_t FS_AltRef_BaroVelD(void)
{
	return (int32_t) (baroVelD * 1000.0f);
}

uint32_t FS_AltRef_Epoch(void)
{
	return epoch;
}

int32_t FS_AltRef_Height(const FS_GNSS_Data_t *data, uint8_t source, int32_t dz_elev, uint8_t *kind)
{
	uint8_t k;
	int32_t h;

	if (FS_AltRef_UsesBaro(source))
	{
		k = FS_ALT_REF_KIND_BARO;
		const float alt = hasBaroAlt ? baroAlt : pressureAltitude(pressure);
		h = (int32_t) ((alt - pressureAltitude(zero.ground_pressure)) * 1000.0f);
	}
	else if (zero.flags & FS_ALT_REF_GNSS)
	{
		k = FS_ALT_REF_KIND_GNSS;
		h = data->hMSL - zero.ground_hmsl;
	}
	else
	{
		k = FS_ALT_REF_KIND_DZ_ELEV;
		h = data->hMSL - dz_elev;
	}

	if (kind) *kind = k;
	return h;
}
