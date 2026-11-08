// Tests/test_replay_parse.c
// Host test for FlySight/replay_parse.c (fork). Run from the Tests folder:
//   gcc -std=c99 -Wall -Wextra -o test_replay_parse test_replay_parse.c \
//       ../FlySight/replay_parse.c ../FlySight/time.c -lm && ./test_replay_parse
#include <stdio.h>
#include <stdlib.h>

#include "../FlySight/replay_parse.h"

static int failures = 0;

#define CHECK(cond) do { if (!(cond)) { \
    printf("FAIL line %d: %s\n", __LINE__, #cond); ++failures; } } while (0)

static void test_gnss(void)
{
    FS_GNSS_Data_t d;
    int64_t gps_ms;

    CHECK(ReplayParse_GNSS("$GNSS,2024-05-01T12:34:56.200Z,52.2296756,-21.0122287,"
        "4123.456,-30.000,40.000,-5.250,1.234,2.345,0.456,17\r\n", &d, &gps_ms));
    CHECK(d.year == 2024 && d.month == 5 && d.day == 1);
    CHECK(d.hour == 12 && d.min == 34 && d.sec == 56 && d.nano == 200000000);
    CHECK(d.iTOW == 304514200u);           // Wednesday 12:34:56.2 + 18 s
    CHECK(gps_ms % REPLAY_WEEK_MSEC == 304514200);
    CHECK(d.lat == 522296756 && d.lon == -210122287);
    CHECK(d.hMSL == 4123456);
    CHECK(d.velN == -30000 && d.velE == 40000 && d.velD == -5250);
    CHECK(d.hAcc == 1234 && d.vAcc == 2345 && d.sAcc == 456);
    CHECK(d.numSV == 17 && d.gpsFix == 3);
    CHECK(d.gSpeed == 5000);               // 50 m/s in cm/s
    CHECK(d.speed == 5027);                // sqrt(50^2 + 5.25^2) m/s
    CHECK(d.heading == 12686990);          // atan2(40, -30) = 126.8699 deg

    // Old files have no numSV. Fewer decimals are permitted.
    CHECK(ReplayParse_GNSS("$GNSS,2024-05-01T12:34:57.000Z,52.2,21.0,100.5,0,0,1,1,1,1\n",
        &d, &gps_ms));
    CHECK(d.hMSL == 100500 && d.velD == 1000 && d.numSV == 0 && d.heading == 0);

    // A row with no fix
    CHECK(ReplayParse_GNSS("$GNSS,2024-05-01T12:34:57.000Z,0.0000000,0.0000000,0.000,"
        "0.000,0.000,0.000,4294967.295,3750000.000,20.000,0\n", &d, &gps_ms));
    CHECK(d.gpsFix == 0);

    // Not GNSS data
    CHECK(!ReplayParse_GNSS("$COL,GNSS,time,lat,lon,hMSL,velN,velE,velD,hAcc,vAcc,sAcc,numSV\n",
        &d, &gps_ms));
    CHECK(!ReplayParse_GNSS("$GNSS,2024-05-01T12:34:57.000Z,52.2,21.0\n", &d, &gps_ms));
    CHECK(!ReplayParse_GNSS("$BARO,1.0,100000.00,20.0\n", &d, &gps_ms));
}

static void test_sensor(void)
{
    int64_t t, gps_ms;
    int32_t p;

    CHECK(ReplayParse_Baro("$BARO,123.456789,101325.25,21.5\r\n", &t, &p));
    CHECK(t == 123456789 && p == 10132525);
    CHECK(!ReplayParse_Baro("$HUM,123.4,50.0,20.0\n", &t, &p));

    CHECK(ReplayParse_Time("$TIME,120.000123,304514.200,2312\r\n", &t, &gps_ms));
    CHECK(t == 120000123);
    CHECK(gps_ms == 2312LL * REPLAY_WEEK_MSEC + 304514200 + REPLAY_LEAP_SECONDS * 1000);
}

int main(void)
{
    test_gnss();
    test_sensor();
    printf(failures ? "%d failures\n" : "All tests passed\n", failures);
    return failures ? 1 : 0;
}
