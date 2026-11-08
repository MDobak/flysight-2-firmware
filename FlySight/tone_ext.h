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


#ifndef TONE_EXT_H_
#define TONE_EXT_H_

#include <stdbool.h>
#include <stdint.h>

/* Fork: tone helpers for audio_control.c. These functions do not use
   hardware. */

#define FS_TONE_EXT_INVALID  INT32_MAX

/* Start again (new mode). */
void FS_ToneExt_Reset(void);

/* Change of value 1 for Mode_2: 9, in units of value 1 per second. The
   result is the slope of a line through the samples of the last second, so
   the noise of one sample has a small effect. rate is the time since the
   last sample (ms). Returns FS_TONE_EXT_INVALID if there are not sufficient
   samples. */
int32_t FS_ToneExt_Change(int32_t value, uint16_t rate);

#endif /* TONE_EXT_H_ */
