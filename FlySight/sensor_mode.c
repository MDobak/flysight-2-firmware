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

#include <stdbool.h>

#include "main.h"
#include "app_common.h"
#include "app_fatfs.h"
#include "alt_ref.h"
#include "baro.h"
#include "config.h"
#include "custom_app.h"
#include "gnss.h"
#include "hum.h"
#include "imu.h"
#include "led.h"
#include "mag.h"
#include "resource_manager.h"
#include "sensor.h"
#include "sensor_ble.h"
#include "sensor_mode.h"
#include "sensor_time.h"
#include "state.h"
#include "vbat.h"

extern UART_HandleTypeDef huart1;
extern ADC_HandleTypeDef hadc1;

static bool adc_ready = false;

static void FS_SensorMode_GNSS_Callback(void)
{
	// Filters for a manual zero from the app. No automatic zero here.
	FS_AltRef_UpdateGNSS(FS_GNSS_GetData(), false);

	// Update BLE characteristic
	Custom_GNSS_Update(FS_GNSS_GetData());
}

void FS_SensorMode_Init(void)
{
	// Initialize LEDs
	FS_LED_SetColour(FS_LED_ORANGE);
	FS_LED_Pulse();

	/* Initialize and start microsecond sensor timer */
	FS_SensorTime_Init();
	FS_SensorTime_Start();

	/* Read the configuration, with the same files as active mode */
	/* The request fails if a BLE file transfer has the card. The card is
	   then mounted already, but do not release it for the transfer. */
	const bool sd = (FS_ResourceManager_RequestResource(FS_RESOURCE_FATFS)
			== FS_RESOURCE_MANAGER_SUCCESS);
	FS_Config_Init();
	FS_Config_Read("/config.txt");
	if (f_chdir("/config") == FR_OK)
	{
		FS_Config_Read(FS_State_Get()->config_filename);
	}
	f_chdir("/");

	/* Keep VCC on after the microSD card is released. VCC also supplies
	   the GNSS receiver. Without it, FS_GNSS_Init waits for an ACK forever
	   and the watchdog resets the FlySight. */
	FS_ResourceManager_RequestResource(FS_RESOURCE_VCC);
	if (sd)
	{
		FS_ResourceManager_ReleaseResource(FS_RESOURCE_FATFS);
	}

	/* Send only the new measurements */
	Sensor_BLE_Reset();

	/* Altitude reference */
	FS_AltRef_Start();

	/* Battery voltage */
	MX_ADC1_Init();
	adc_ready = (HAL_ADCEx_Calibration_Start(&hadc1, ADC_SINGLE_ENDED) == HAL_OK);
	if (adc_ready)
	{
		FS_VBAT_Init();
	}

	/* GNSS */
	MX_USART1_UART_Init();
	FS_GNSS_DataReady_SetCallback(FS_SensorMode_GNSS_Callback);
	FS_GNSS_Init();
	FS_GNSS_Start();

	/* Sensors on the I2C bus. Start all of them, also if the
	   configuration does not log them. */
	FS_Baro_Start();
	FS_Hum_Start();
	FS_Mag_Start();
	FS_Sensor_Start();

	/* IMU */
	FS_IMU_Start();
}

void FS_SensorMode_DeInit(void)
{
	/* IMU */
	FS_IMU_Stop();

	/* Sensors on the I2C bus */
	FS_Sensor_Stop();
	FS_Mag_Stop();
	FS_Hum_Stop();
	FS_Baro_Stop();

	/* GNSS */
	FS_GNSS_DataReady_SetCallback(NULL);
	FS_GNSS_DeInit();
	HAL_UART_DeInit(&huart1);

	/* Battery voltage */
	if (adc_ready)
	{
		FS_VBAT_DeInit();
	}
	HAL_ADC_DeInit(&hadc1);
	adc_ready = false;

	/* Stop microsecond sensor timer */
	FS_SensorTime_Stop();

	/* Turn off VCC (GNSS receiver) */
	FS_ResourceManager_ReleaseResource(FS_RESOURCE_VCC);

	// Turn off LEDs
	FS_LED_Off();
}
