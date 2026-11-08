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
#include <string.h>

#include "replay_parse.h"
#include "time.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#define NO_FIX_ACC  1000000 /* Horizontal accuracy (mm) of a row with no fix */

/* Seconds from the GPS epoch (1980-01-06) to the mk_gmtime epoch (2000-01-01) */
#define GPS_TO_Y2K  630720000LL

/* Read a decimal number, for example "-12.3456", as an integer with the
   given number of decimals. Extra decimals are cut. Moves *p to the
   character after the number. */
static bool parseFixed(const char **p, uint8_t decimals, int64_t *value)
{
	const char *s = *p;
	bool negative = false;
	bool digits = false;
	int64_t v = 0;
	uint8_t d = 0;

	if (*s == '-')
	{
		negative = true;
		++s;
	}
	else if (*s == '+')
	{
		++s;
	}

	while ((*s >= '0') && (*s <= '9'))
	{
		v = v * 10 + (*s - '0');
		digits = true;
		++s;
	}

	if (*s == '.')
	{
		++s;
		while ((*s >= '0') && (*s <= '9'))
		{
			if (d < decimals)
			{
				v = v * 10 + (*s - '0');
				++d;
			}
			digits = true;
			++s;
		}
	}

	if (!digits) return false;

	for (; d < decimals; ++d)
	{
		v *= 10;
	}

	*value = negative ? -v : v;
	*p = s;
	return true;
}

/* The end of a field: a comma (then go to the next field) or the end
   of the line. */
static bool fieldEnd(const char **p)
{
	const char c = **p;

	if (c == ',')
	{
		++(*p);
		return true;
	}

	return (c == '\0') || (c == '\r') || (c == '\n');
}

static bool parseField(const char **p, uint8_t decimals, int64_t *value)
{
	return parseFixed(p, decimals, value) && fieldEnd(p);
}

static bool parseDigits(const char **p, uint8_t count, uint32_t *value)
{
	uint32_t v = 0;
	uint8_t i;

	for (i = 0; i < count; ++i)
	{
		const char c = (*p)[i];
		if ((c < '0') || (c > '9')) return false;
		v = v * 10 + (c - '0');
	}

	*p += count;
	*value = v;
	return true;
}

static bool expect(const char **p, char c)
{
	if (**p != c) return false;
	++(*p);
	return true;
}

/* "YYYY-MM-DDTHH:MM:SS.mmmZ" (the milliseconds are optional) */
static bool parseTime(const char **p, FS_GNSS_Data_t *data, int64_t *y2k_ms)
{
	uint32_t year, month, day, hour, min, sec, ms = 0;
	const char *s = *p;

	if (!parseDigits(&s, 4, &year) || !expect(&s, '-') ||
			!parseDigits(&s, 2, &month) || !expect(&s, '-') ||
			!parseDigits(&s, 2, &day) || !expect(&s, 'T') ||
			!parseDigits(&s, 2, &hour) || !expect(&s, ':') ||
			!parseDigits(&s, 2, &min) || !expect(&s, ':') ||
			!parseDigits(&s, 2, &sec))
	{
		return false;
	}

	if (*s == '.')
	{
		int64_t frac;
		if (!parseFixed(&s, 3, &frac)) return false;
		ms = (uint32_t) frac;
	}

	if (!expect(&s, 'Z') || !expect(&s, ',')) return false;

	if ((month < 1) || (month > 12) || (day < 1) || (day > 31) ||
			(hour > 23) || (min > 59) || (sec > 60) || (year < 2000))
	{
		return false;
	}

	data->year  = (uint16_t) year;
	data->month = (uint8_t) month;
	data->day   = (uint8_t) day;
	data->hour  = (uint8_t) hour;
	data->min   = (uint8_t) min;
	data->sec   = (uint8_t) sec;
	data->nano  = (int32_t) ms * 1000000;

	*y2k_ms = (int64_t) mk_gmtime((uint16_t) year, (uint8_t) month, (uint8_t) day,
			(uint8_t) hour, (uint8_t) min, (uint8_t) sec) * 1000 + ms;
	*p = s;
	return true;
}

bool ReplayParse_GNSS(const char *line, FS_GNSS_Data_t *data, int64_t *gps_ms)
{
	const char *p = line;
	int64_t y2k_ms, lat, lon, hMSL, velN, velE, velD, hAcc, vAcc, sAcc, numSV;
	double h, a;

	if (strncmp(p, "$GNSS,", 6) != 0) return false;
	p += 6;

	memset(data, 0, sizeof(*data));

	if (!parseTime(&p, data, &y2k_ms) ||
			!parseField(&p, 7, &lat) ||
			!parseField(&p, 7, &lon) ||
			!parseField(&p, 3, &hMSL) ||
			!parseField(&p, 3, &velN) ||
			!parseField(&p, 3, &velE) ||
			!parseField(&p, 3, &velD) ||
			!parseField(&p, 3, &hAcc) ||
			!parseField(&p, 3, &vAcc) ||
			!parseField(&p, 3, &sAcc))
	{
		return false;
	}

	/* numSV is not in old files */
	if (!parseFixed(&p, 0, &numSV))
	{
		numSV = 0;
	}

	*gps_ms = y2k_ms + (GPS_TO_Y2K + REPLAY_LEAP_SECONDS) * 1000;

	data->iTOW   = (uint32_t) (*gps_ms % REPLAY_WEEK_MSEC);
	data->lat    = (int32_t) lat;
	data->lon    = (int32_t) lon;
	data->hMSL   = (int32_t) hMSL;
	data->velN   = (int32_t) velN;
	data->velE   = (int32_t) velE;
	data->velD   = (int32_t) velD;
	data->hAcc   = (uint32_t) hAcc;
	data->vAcc   = (uint32_t) vAcc;
	data->sAcc   = (uint32_t) sAcc;
	data->numSV  = (uint8_t) numSV;

	/* The log has all rows, also rows with no fix. The fix type is not
	   in the log. A row with a large accuracy value has no fix. */
	data->gpsFix = ((hAcc > NO_FIX_ACC) || ((lat == 0) && (lon == 0))) ? 0 : 3;

	/* The GNSS receiver gives these values. The track does not have them. */
	h = sqrt((double) velN * velN + (double) velE * velE);
	data->gSpeed = (int32_t) lround(h / 10.0);
	data->speed  = (int32_t) lround(sqrt(h * h + (double) velD * velD) / 10.0);
	a = atan2((double) velE, (double) velN) * 180.0 / M_PI;
	if (a < 0) a += 360.0;
	data->heading = (int32_t) lround(a * 100000.0);

	return true;
}

bool ReplayParse_Baro(const char *line, int64_t *time_us, int32_t *pressure)
{
	const char *p = line;
	int64_t t, pr;

	if (strncmp(p, "$BARO,", 6) != 0) return false;
	p += 6;

	if (!parseField(&p, 6, &t) || !parseFixed(&p, 2, &pr)) return false;

	*time_us = t;
	*pressure = (int32_t) pr;
	return true;
}

bool ReplayParse_Time(const char *line, int64_t *time_us, int64_t *gps_ms)
{
	const char *p = line;
	int64_t t, tow, week;

	if (strncmp(p, "$TIME,", 6) != 0) return false;
	p += 6;

	if (!parseField(&p, 6, &t) || !parseField(&p, 3, &tow) ||
			!parseFixed(&p, 0, &week))
	{
		return false;
	}

	*time_us = t;
	*gps_ms = week * REPLAY_WEEK_MSEC + tow;
	return true;
}
