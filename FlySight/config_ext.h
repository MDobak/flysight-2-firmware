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


/* Settings that this fork adds to CONFIG.TXT. They are in a separate
   struct, so that config.c and config.h stay almost the same as upstream.
   config.c calls the three hooks below. See Docs/fork.md. */

#ifndef CONFIG_EXT_H_
#define CONFIG_EXT_H_

#include <stdint.h>

#include "ff.h"

#define FS_CONFIG_ALT_SOURCE_GNSS   0
#define FS_CONFIG_ALT_SOURCE_BARO   1

#define FS_CONFIG_ALT_FORMAT_FULL   0
#define FS_CONFIG_ALT_FORMAT_SHORT  1

typedef struct
{
	uint8_t alt_source;   // Alt_Source: altitude for alarms, windows and calls
	uint8_t alt_zero;     // Alt_Zero: automatic ground level
	uint8_t alt_format;   // Alt_Format: how the altitude is spoken
} FS_ConfigExt_Data_t;

/* Hook: end of FS_Config_Init. Sets the defaults. */
void FS_ConfigExt_Init(void);

/* Hook: FS_Config_Read, for each line with a name and a value. */
void FS_ConfigExt_Handle(const char *name, const char *value);

/* Hook: FS_Config_Write, after the default text. */
void FS_ConfigExt_Write(FIL *file);

const FS_ConfigExt_Data_t *FS_ConfigExt_Get(void);

#endif /* CONFIG_EXT_H_ */
