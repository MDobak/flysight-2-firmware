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

#include "mode_ext.h"
#include "sensor_mode.h"

bool FS_ModeExt_Init(FS_Mode_State_t state)
{
	bool handled = true;

	switch (state)
	{
	case FS_MODE_STATE_SENSOR:
		FS_SensorMode_Init();
		break;

	default:
		handled = false;
		break;
	}

	return handled;
}

void FS_ModeExt_DeInit(FS_Mode_State_t state)
{
	switch (state)
	{
	case FS_MODE_STATE_SENSOR:
		FS_SensorMode_DeInit();
		break;

	default:
		break;
	}
}
