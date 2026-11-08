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

#ifndef MODE_EXT_H_
#define MODE_EXT_H_

#include <stdbool.h>

#include "mode.h"

/* Fork modes (for example, sensor mode). mode.c has one handler for all
   fork modes. These modes stop on a button press, on a request for sleep
   mode, on a terminal action, and on FS_MODE_EVENT_EXT_STOP. */

/* Start a fork mode. Returns false if the state is not a fork mode. */
bool FS_ModeExt_Init(FS_Mode_State_t state);

/* Stop a fork mode. */
void FS_ModeExt_DeInit(FS_Mode_State_t state);

#endif /* MODE_EXT_H_ */
