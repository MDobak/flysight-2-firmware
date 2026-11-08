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


/* Device State control point commands that this fork adds. Opcodes
   0x15 and up. device_state.c calls DeviceStateExt_Handle() for each
   opcode that it does not know.

   The mode requests (for example DS_CMD_REQUEST_SENSOR, 0x15) stay in
   device_state.c, because they use its pending request logic. */

#ifndef DEVICE_STATE_EXT_H_
#define DEVICE_STATE_EXT_H_

#include <stdint.h>

#define DS_CMD_SET_ALT_ZERO    0x16 // Payload: (none). Active or sensor mode only.
                                    // Response Data: see alt_zero_response()
#define DS_CMD_GET_ALT_ZERO    0x17 // Payload: (none)
                                    // Response Data: see alt_zero_response()

/* Returns a CP_STATUS_* value. Puts the response data in response and
   its length in response_len. */
uint8_t DeviceStateExt_Handle(uint8_t opcode, const uint8_t *params, uint8_t params_len,
                              uint8_t *response, uint8_t *response_len);

#endif /* DEVICE_STATE_EXT_H_ */
