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

#ifndef ALT_REF_H_
#define ALT_REF_H_

#include <stdbool.h>
#include <stdint.h>

#include "gnss.h"

/* The ground level (zero) for the altitude alarms, the windows and the
   altitude calls. RAM keeps the zero in sleep mode. A reset clears it. */

#define FS_ALT_REF_GNSS  0x01 /* The GNSS ground level is set */
#define FS_ALT_REF_BARO  0x02 /* The barometer ground level is set */
#define FS_ALT_REF_AUTO  0x04 /* The last zero came from the automatic zero */

/* What FS_AltRef_Height used for the height */
#define FS_ALT_REF_KIND_DZ_ELEV 0 /* GNSS altitude above dz_elev */
#define FS_ALT_REF_KIND_GNSS    1 /* GNSS altitude above the GNSS zero */
#define FS_ALT_REF_KIND_BARO    2 /* Barometer altitude above the barometer zero */

typedef struct
{
	uint8_t flags;
	int32_t ground_hmsl;     /* GNSS altitude of the ground (mm above sea level) */
	int32_t ground_pressure; /* Pressure at the ground (Pa * 100) */
} FS_AltRef_Zero_t;

/* Clear the filters at the start of a mode. This keeps the zero. */
void FS_AltRef_Start(void);

/* Add a barometer measurement (time in us). Interrupt context is permitted. */
void FS_AltRef_UpdateBaro(int32_t pressure, uint64_t time);

/* Add a GNSS measurement. With auto_zero, the zero is set after
   1 minute with no movement. */
void FS_AltRef_UpdateGNSS(const FS_GNSS_Data_t *data, bool auto_zero);

/* Set the zero to the current position. Returns the FS_ALT_REF_GNSS and
   FS_ALT_REF_BARO flags of the parts that were set. */
uint8_t FS_AltRef_SetZero(void);

/* Set the parts in mask (FS_ALT_REF_GNSS, FS_ALT_REF_BARO) to the values
   (ground_hmsl in mm, ground_pressure in Pa * 100). Returns the flags of
   the parts that were set. */
uint8_t FS_AltRef_SetZeroValues(uint8_t mask, int32_t ground_hmsl, int32_t ground_pressure);

const FS_AltRef_Zero_t *FS_AltRef_GetZero(void);

/* Set the zero to saved values. The replay mode uses this to keep the
   zero of the real jump. */
void FS_AltRef_RestoreZero(const FS_AltRef_Zero_t *saved);

/* Height above the ground (mm) from the altitude source (0 = GNSS,
   1 = barometer). Without a zero, the height is the GNSS altitude
   above dz_elev. */
int32_t FS_AltRef_Height(const FS_GNSS_Data_t *data, uint8_t source, int32_t dz_elev, uint8_t *kind);

/* True if FS_AltRef_Height uses the barometer for this source. */
bool FS_AltRef_UsesBaro(uint8_t source);

/* Vertical speed from the barometer (mm/s, down is positive). */
int32_t FS_AltRef_BaroVelD(void);

/* Changes each time that the zero changes. A height difference across a
   change is not a real movement. */
uint32_t FS_AltRef_Epoch(void);

#endif /* ALT_REF_H_ */
