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

#include <string.h>

#include "main.h"
#include "app_common.h"
#include "baro.h"
#include "hum.h"
#include "imu.h"
#include "mag.h"
#include "sensor_ble.h"
#include "sensor_time.h"
#include "stm32_seq.h"
#include "vbat.h"

static volatile bool s_enabled = false;
static volatile uint64_t s_last_packet = 0;

/* Time of the last value that was sent for each sensor */
static uint64_t s_sent_baro;
static uint64_t s_sent_hum;
static uint64_t s_sent_mag;
static uint64_t s_sent_imu;
static uint64_t s_sent_vbat;

void Sensor_BLE_SetEnabled(bool enabled)
{
    if (enabled)
    {
        Sensor_BLE_Reset();
    }
    s_enabled = enabled;
}

void Sensor_BLE_Reset(void)
{
    uint32_t primask_bit = __get_PRIMASK();
    __disable_irq();

    s_sent_baro = FS_Baro_GetData()->time;
    s_sent_hum  = FS_Hum_GetData()->time;
    s_sent_mag  = FS_Mag_GetData()->time;
    s_sent_imu  = FS_IMU_GetData()->time;
    s_sent_vbat = FS_VBAT_GetData()->time;
    s_last_packet = 0;

    __set_PRIMASK(primask_bit);
}

void Sensor_BLE_DataReady(void)
{
    uint64_t now;
    bool due;
    uint32_t primask_bit;

    if (!s_enabled) return;

    now = FS_SensorTime_GetTicks();

    primask_bit = __get_PRIMASK();
    __disable_irq();
    // The sensor timer starts again at zero in each mode
    due = (now < s_last_packet) || (now - s_last_packet >= SENSOR_BLE_INTERVAL_US);
    if (due)
    {
        s_last_packet = now;
    }
    __set_PRIMASK(primask_bit);

    if (due)
    {
        UTIL_SEQ_SetTask(1 << CFG_TASK_FS_SENSOR_BLE_ID, CFG_SCH_PRIO_1);
    }
}

uint8_t Sensor_BLE_Build(uint8_t *dst)
{
    FS_Baro_Data_t baro;
    FS_Hum_Data_t  hum;
    FS_Mag_Data_t  mag;
    FS_IMU_Data_t  imu;
    FS_VBAT_Data_t vbat;
    uint8_t mask = 0;
    uint8_t *p = dst + 1;
    int16_t i16;

    // Copy the values. The sensor interrupts change them.
    uint32_t primask_bit = __get_PRIMASK();
    __disable_irq();
    baro = *FS_Baro_GetData();
    hum  = *FS_Hum_GetData();
    mag  = *FS_Mag_GetData();
    imu  = *FS_IMU_GetData();
    vbat = *FS_VBAT_GetData();
    __set_PRIMASK(primask_bit);

    if (baro.time != s_sent_baro)                       /* barometer     */
    {
        s_sent_baro = baro.time;
        mask |= SENSOR_BLE_BIT_BARO;
        memcpy(p, &baro.pressure, 4);               p += 4;   /* Pa * 100      */
        memcpy(p, &baro.temperature, 2);            p += 2;   /* deg C * 100   */
    }

    if (hum.time != s_sent_hum)                         /* humidity      */
    {
        s_sent_hum = hum.time;
        mask |= SENSOR_BLE_BIT_HUM;
        memcpy(p, &hum.humidity, 2);                p += 2;   /* % * 10        */
        i16 = (int16_t) hum.temperature;
        memcpy(p, &i16, 2);                         p += 2;   /* deg C * 10    */
    }

    if (mag.time != s_sent_mag)                         /* magnetometer  */
    {
        s_sent_mag = mag.time;
        mask |= SENSOR_BLE_BIT_MAG;
        memcpy(p, &mag.x, 2);                       p += 2;   /* gauss * 1000  */
        memcpy(p, &mag.y, 2);                       p += 2;
        memcpy(p, &mag.z, 2);                       p += 2;
        memcpy(p, &mag.temperature, 2);             p += 2;   /* deg C * 10    */
    }

    if (imu.time != s_sent_imu)                         /* IMU           */
    {
        s_sent_imu = imu.time;
        mask |= SENSOR_BLE_BIT_IMU;
        memcpy(p, &imu.wx, 4);                      p += 4;   /* deg/s * 1000  */
        memcpy(p, &imu.wy, 4);                      p += 4;
        memcpy(p, &imu.wz, 4);                      p += 4;
        memcpy(p, &imu.ax, 4);                      p += 4;   /* g * 100000    */
        memcpy(p, &imu.ay, 4);                      p += 4;
        memcpy(p, &imu.az, 4);                      p += 4;
        memcpy(p, &imu.temperature, 2);             p += 2;   /* deg C * 100   */
    }

    if (vbat.time != s_sent_vbat)                       /* battery       */
    {
        s_sent_vbat = vbat.time;
        mask |= SENSOR_BLE_BIT_VBAT;
        memcpy(p, &vbat.voltage, 2);                p += 2;   /* mV            */
    }

    dst[0] = mask;
    return (uint8_t)(p - dst);
}
