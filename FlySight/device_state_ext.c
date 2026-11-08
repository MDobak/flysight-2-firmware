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


#include "main.h"
#include "alt_ref.h"
#include "config.h"
#include "config_ext.h"
#include "control_point_protocol.h"
#include "device_state_ext.h"
#include "mode.h"
#include "replay_mode.h"

static void put_i32(uint8_t *buf, int32_t value)
{
    uint32_t v = (uint32_t)value;
    buf[0] = (uint8_t)v;
    buf[1] = (uint8_t)(v >> 8);
    buf[2] = (uint8_t)(v >> 16);
    buf[3] = (uint8_t)(v >> 24);
}

/* Response data for the altitude zero commands (16 bytes, little-endian):
 *   [0]     Parts set by this command (FS_ALT_REF_GNSS | FS_ALT_REF_BARO)
 *   [1]     Zero flags (FS_ALT_REF_GNSS | FS_ALT_REF_BARO | FS_ALT_REF_AUTO)
 *   [2..5]  GNSS ground level (mm above sea level)
 *   [6..9]  Ground pressure (Pa * 100)
 *   [10]    Alt_Source of the last configuration read
 *   [11]    Alt_Zero of the last configuration read
 *   [12..15] DZ_Elev of the last configuration read (mm)
 */
static int32_t get_i32(const uint8_t *buf)
{
    return (int32_t)((uint32_t)buf[0] | ((uint32_t)buf[1] << 8) |
                     ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 24));
}

static uint8_t alt_zero_response(uint8_t set, uint8_t *buf)
{
    const FS_AltRef_Zero_t *zero = FS_AltRef_GetZero();

    buf[0] = set;
    buf[1] = zero->flags;
    put_i32(&buf[2], zero->ground_hmsl);
    put_i32(&buf[6], zero->ground_pressure);
    buf[10] = FS_ConfigExt_Get()->alt_source;
    buf[11] = FS_ConfigExt_Get()->alt_zero;
    put_i32(&buf[12], FS_Config_Get()->dz_elev);
    return 16;
}

/* Response data for DS_CMD_GET_REPLAY (11 bytes, little-endian):
 *   [0]     State (FS_ReplayMode_State_t)
 *   [1]     Error (FS_ReplayMode_Error_t)
 *   [2..5]  Position: time of the last sample after the first sample (ms)
 *   [6..9]  Height above the ground of the last sample (mm)
 *   [10]    Source of the height (FS_ALT_REF_KIND_*)
 */
static uint8_t replay_response(uint8_t *buf)
{
    const FS_ReplayMode_Status_t *status = FS_ReplayMode_GetStatus();

    buf[0] = status->state;
    buf[1] = status->error;
    put_i32(&buf[2], (int32_t)status->pos_ms);
    put_i32(&buf[6], status->height);
    buf[10] = status->kind;
    return 11;
}

uint8_t DeviceStateExt_Handle(uint8_t opcode, const uint8_t *params, uint8_t params_len,
                              uint8_t *response, uint8_t *response_len)
{
    switch (opcode) {
        case DS_CMD_SET_ALT_ZERO:
            if (params_len == 9) {
                /* Values from the app. Accepted in all modes. */
                const uint8_t mask = params[0];
                if ((mask == 0) || (mask & ~(FS_ALT_REF_GNSS | FS_ALT_REF_BARO))) {
                    return CP_STATUS_INVALID_PARAMETER;
                }
                *response_len = alt_zero_response(
                    FS_AltRef_SetZeroValues(mask, get_i32(&params[1]), get_i32(&params[5])), response);
                return CP_STATUS_SUCCESS;
            }
            if (params_len != 0) {
                return CP_STATUS_INVALID_PARAMETER;
            }
            if ((FS_Mode_State() != FS_MODE_STATE_ACTIVE) &&
                (FS_Mode_State() != FS_MODE_STATE_SENSOR)) {
                return CP_STATUS_OPERATION_NOT_PERMITTED;
            }
            *response_len = alt_zero_response(FS_AltRef_SetZero(), response);
            return CP_STATUS_SUCCESS;

        case DS_CMD_GET_ALT_ZERO:
            if (params_len != 0) {
                return CP_STATUS_INVALID_PARAMETER;
            }
            *response_len = alt_zero_response(0, response);
            return CP_STATUS_SUCCESS;

        case DS_CMD_SET_REPLAY: {
            if (params_len < 3) {
                return CP_STATUS_INVALID_PARAMETER;
            }
            if (FS_Mode_State() == FS_MODE_STATE_REPLAY) {
                return CP_STATUS_OPERATION_NOT_PERMITTED;
            }
            const uint16_t value = (uint16_t)(params[0] | (params[1] << 8));
            const uint8_t flags = ((value & 0x8000) ? FS_REPLAY_FLAG_ZERO : 0) |
                                  ((value & 0x4000) ? FS_REPLAY_FLAG_LANDING : 0);
            if (!FS_ReplayMode_Set((const char *)&params[2], params_len - 2,
                                   value & 0x3FFF, flags)) {
                return CP_STATUS_INVALID_PARAMETER;
            }
            return CP_STATUS_SUCCESS;
        }

        case DS_CMD_GET_REPLAY:
            if (params_len != 0) {
                return CP_STATUS_INVALID_PARAMETER;
            }
            *response_len = replay_response(response);
            return CP_STATUS_SUCCESS;

        default:
            return CP_STATUS_CMD_NOT_SUPPORTED;
    }
}
