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


#include <stdlib.h>
#include <string.h>

#include "config_ext.h"

static FS_ConfigExt_Data_t configExt;

static const char defaultConfigExt[] =
		"\n"
		"; Altitude reference\n"
		"\n"
		"Alt_Source:    0 ; Altitude for alarms, windows and calls\n"
		"                 ;   0 = GNSS\n"
		"                 ;   1 = Barometer\n"
		"Alt_Zero:      0 ; Set the ground level automatically\n"
		"                 ;   0 = Off (use DZ_Elev)\n"
		"                 ;   1 = After 1 minute with no movement\n"
		"Alt_Format:    0 ; How the altitude is spoken\n"
		"                 ;   0 = Full (2500 = two thousand five hundred)\n"
		"                 ;   1 = Short (2500 = 2 5, below 1000 full)\n";

void FS_ConfigExt_Init(void)
{
	configExt.alt_source = FS_CONFIG_ALT_SOURCE_GNSS;
	configExt.alt_zero   = 0;
	configExt.alt_format = FS_CONFIG_ALT_FORMAT_FULL;
}

void FS_ConfigExt_Handle(const char *name, const char *value)
{
	const int32_t val = atol(value);

	#define HANDLE_EXT(s,w,t) \
		if ((t) && !strcmp(name, (s))) { (w) = val; }

	HANDLE_EXT("Alt_Source", configExt.alt_source, val == 0 || val == 1);
	HANDLE_EXT("Alt_Zero",   configExt.alt_zero,   val == 0 || val == 1);
	HANDLE_EXT("Alt_Format", configExt.alt_format, val == 0 || val == 1);

	#undef HANDLE_EXT
}

void FS_ConfigExt_Write(FIL *file)
{
	f_puts(defaultConfigExt, file);
}

const FS_ConfigExt_Data_t *FS_ConfigExt_Get(void)
{
	return &configExt;
}
