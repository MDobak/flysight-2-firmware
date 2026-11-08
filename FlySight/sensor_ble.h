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

#ifndef SENSOR_BLE_H_
#define SENSOR_BLE_H_

#include <stdbool.h>
#include <stdint.h>

/* ------------------------------------------------------------------ */
/* Packet layout                                                      */
/* ------------------------------------------------------------------ */
/* Byte 0 is a mask. It shows which sensors follow, in this order.    */
#define SENSOR_BLE_BIT_BARO         0x01u   /* 6 bytes                  */
#define SENSOR_BLE_BIT_HUM          0x02u   /* 4 bytes                  */
#define SENSOR_BLE_BIT_MAG          0x04u   /* 8 bytes                  */
#define SENSOR_BLE_BIT_IMU          0x08u   /* 26 bytes                 */
#define SENSOR_BLE_BIT_VBAT         0x10u   /* 2 bytes                  */

#define SENSOR_BLE_MAX_LEN          47u

/* Minimum time between two packets (us) */
#define SENSOR_BLE_INTERVAL_US      250000u

/* ------------------------------------------------------------------ */
/* Public API                                                         */
/* ------------------------------------------------------------------ */

/* Turn the stream on or off. When it is on, Sensor_BLE_Reset() runs. */
void    Sensor_BLE_SetEnabled(bool enabled);

/* Ignore the values that the sensors have now. The next packet only
   contains new measurements. */
void    Sensor_BLE_Reset(void);

/* Call this when a sensor has a new value. It can run in an interrupt.
   It starts the BLE task when a packet is due. */
void    Sensor_BLE_DataReady(void);

/* Put the new measurements in dst. Returns the packet length. A length
   of 1 means that no sensor has a new value. */
uint8_t Sensor_BLE_Build(uint8_t *dst);

#endif /* SENSOR_BLE_H_ */
