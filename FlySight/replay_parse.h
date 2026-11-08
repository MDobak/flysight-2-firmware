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

#ifndef REPLAY_PARSE_H_
#define REPLAY_PARSE_H_

#include <stdbool.h>
#include <stdint.h>

#include "gnss.h"

/* Parsers for the lines of track.csv and sensor.csv. These functions do
   not use hardware. Tests/test_replay_parse.c tests them on a computer. */

#define REPLAY_LEAP_SECONDS  18          /* GPS time - UTC (since 2017) */
#define REPLAY_WEEK_MSEC     604800000LL

/* A $GNSS line of track.csv. Sets all fields of data. gpsFix is 3, or 0
   if the row has no fix (hAcc above 1 km).
   gps_ms is the time in ms since the GPS epoch (1980-01-06). */
bool ReplayParse_GNSS(const char *line, FS_GNSS_Data_t *data, int64_t *gps_ms);

/* A $BARO line of sensor.csv. time_us is the sensor time (us).
   pressure is in Pa * 100. */
bool ReplayParse_Baro(const char *line, int64_t *time_us, int32_t *pressure);

/* A $TIME line of sensor.csv. time_us is the sensor time (us) of the
   time pulse. gps_ms is the GPS time of the pulse (ms since the GPS epoch). */
bool ReplayParse_Time(const char *line, int64_t *time_us, int64_t *gps_ms);

#endif /* REPLAY_PARSE_H_ */
