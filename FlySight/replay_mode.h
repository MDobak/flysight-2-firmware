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

#ifndef REPLAY_MODE_H_
#define REPLAY_MODE_H_

#include <stdbool.h>
#include <stdint.h>

/* Replay mode plays a recorded track through the audio. It reads track.csv
   and sensor.csv (if it is there) of a log folder at real-time speed. It
   uses the current configuration. There is no GNSS, no log, and no
   ActiveLook. A BLE client sets the track with FS_ReplayMode_Set and then
   starts the mode from sleep mode. */

#define FS_REPLAY_FOLDER_LEN   17   /* For example "24-05-01/12-34-56" */
#define FS_REPLAY_FLAG_ZERO    0x01 /* Set the ground level at the start of the track */

typedef enum
{
	FS_REPLAY_STATE_IDLE = 0,
	FS_REPLAY_STATE_SEEKING,  /* Goes to the start position (no audio) */
	FS_REPLAY_STATE_PLAYING,
	FS_REPLAY_STATE_DONE,     /* The track is at its end */
	FS_REPLAY_STATE_STOPPED,  /* Stopped before the end */
	FS_REPLAY_STATE_ERROR
} FS_ReplayMode_State_t;

typedef enum
{
	FS_REPLAY_ERROR_NONE = 0,
	FS_REPLAY_ERROR_NO_TRACK,   /* No track was set */
	FS_REPLAY_ERROR_SD,         /* The microSD card is not available */
	FS_REPLAY_ERROR_OPEN,       /* track.csv cannot be opened */
	FS_REPLAY_ERROR_NO_DATA     /* track.csv has no GNSS data */
} FS_ReplayMode_Error_t;

typedef struct
{
	uint8_t  state;     /* FS_ReplayMode_State_t */
	uint8_t  error;     /* FS_ReplayMode_Error_t */
	uint32_t pos_ms;    /* Time of the last sample after the first sample */
	int32_t  height;    /* Height above the ground (mm) of the last sample */
	uint8_t  kind;      /* Source of the height (FS_ALT_REF_KIND_*) */
} FS_ReplayMode_Status_t;

/* Set the track. folder is the log folder without the leading slash, with
   len characters (no NUL). offset_s is the start position (s after the
   first sample). Returns false if the values are not valid. */
bool FS_ReplayMode_Set(const char *folder, uint8_t len, uint16_t offset_s, uint8_t flags);

const FS_ReplayMode_Status_t *FS_ReplayMode_GetStatus(void);

void FS_ReplayMode_Init(void);
void FS_ReplayMode_DeInit(void);

#endif /* REPLAY_MODE_H_ */
