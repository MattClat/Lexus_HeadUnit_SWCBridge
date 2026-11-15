# 4x4shop.ca OEM Fit Replacement Head Unit (Lexus IS350)

## System Overview & Hardware
* **Target Vehicle:** 2014–2019 Lexus IS (IS250 / IS350)[cite: 1, 4]
* **Product Model:** `LEXUS_LEXUS_MRW1920X720` / Type: `MRW_LEXUS`[cite: 4]
* **Serial Number (SN):** [redacted]
* **IMEI:** [redacted]
* **Factory Control Type:** Lexus Navigation with Jog Dial / Joystick Control[cite: 1, 4]
* **Factory Display Replaced:** OEM 8.8" Display[cite: 1, 4]
* **Aftermarket Display:** 10.25" Anti-Glare Blue Screen[cite: 1, 4]
* **LCM Driver / Panel:** `icn6201_1920x720_drv`[cite: 2, 4]
* **Touch Controller:** `gt9xx` (Goodix multi-touch)[cite: 2, 4]
* **Display Resolution & Refresh:** 1920 x 720 @ 47.4 Hz (Density: 240 hdpi)[cite: 2, 4]
* **Operating System (Actual):** Android 7.0 (Nougat, API Level 24) *(Skinned/reported in UI as Android 9.0)*[cite: 4]
* **Security Patch Level:** February 5, 2019[cite: 4]
* **Build / Fingerprint:** `NRD90M` / `alps/lexus_mrw1920x720` (Kernel: `3.18.35+`, built Mar 9 2020)[cite: 2, 4]
* **Board / System Integrator:** SmartPie (`smartpie@smartpie-Server`)[cite: 2, 4]
* **SoC / Platform:** MediaTek MT8783T / MT6735 platform[cite: 2, 4]
* **CPU:** 8x ARM Cortex-A53 @ 299 – 1495 MHz (64-bit aarch64, ARMv8-A)[cite: 2, 4]
* **GPU:** ARM Mali-T720 MP3 (OpenGL ES 3.1)[cite: 2, 4]
* **RAM:** 4 GB LPDDR3 @ 1600 MHz[cite: 2, 4]
* **Internal Storage:** 64 GB eMMC (~52.44 GiB accessible partition space)[cite: 2, 4]
* **Power Management / Audio:** PMIC `mt6311`, Charger `ncp1854`, Sound driver `mtsndcard`[cite: 2, 4]
* **Wi-Fi / Connectivity Subsystem:** `CONSYS_MT6735`[cite: 2, 4]
* **Tech Support:** First Year Included (4x4shop.ca)[cite: 1, 4]

---

## Firmware & Software Build
* **MCU Version:** `LEXUS_V5.0_0321`[cite: 4]
* **CAN-Bus Protocol / Box:** `LY-Lexus-V206,F,01` (LDir / Luyuan / Luyao CAN protocol family)[cite: 4]
* **System Build:** `4.64.8core.191207`[cite: 4]
* **App Version:** `V2.0.3.1217h`[cite: 4]
* **Software Version:** `1.0.3.1123`[cite: 4]
* **ATE Tool Status:** `Pass`[cite: 4]
* **Factory Settings PIN:** `666666` (Confirmed)[cite: 3, 4]

---

## Installed Audio & Multimedia Apps
* **Poweramp Music Player:**
  * **Build Series:** Builds 1002–1025 (Changelog: Feature Pack #1, MusicBrainz / Cover Art Archive search, customizable button row)
  * **Audio Engine:** OpenSL ES / AudioTrack output (routed to OEM AUX)
  * **Supported Media:** Hi-Res Local Audio (FLAC, ALAC, MP3, AAC, DSD via USB/SD storage)
* **Smartphone Integration:** Apple CarPlay & Android Auto (Wired via external USB dongle)[cite: 2, 4]
* **Companion App:** AutoKit.apk / ZLink (depending on dongle model)[cite: 3, 4]

---

## Connectivity & Multimedia
* **Wireless Protocols:** Wi-Fi (built-in MT6735 subsystem), Bluetooth with A2DP audio streaming[cite: 2, 4]
* **Cellular / Modem:** MediaTek MT6735 modem (`MOLY.LR9.W1444.MD.LWTG.MP.V123.P10`)[cite: 4]
* **Navigation:** Standalone GPS support (external antenna) + navigation apps via Android / CarPlay[cite: 1, 4]
* **Audio Routing:** OEM AUX integration / Bluetooth A2DP[cite: 1, 4]
* **USB Ports:** USB Multimedia Interface (Dongle / Flash Drives)[cite: 1, 4]
* **Camera Inputs:** Retains OEM Factory Backup Camera (No aftermarket camera added)[cite: 1, 4]

---

## Factory System Retention
* Retains factory Lexus Enform / OEM infotainment interface (switchable on-the-fly)[cite: 1, 4]
* Retains factory Jog Dial / Joystick navigation controller[cite: 1, 4]
* Retains steering wheel audio & multifunction controls via CAN-bus decoder[cite: 1, 4]
* Retains factory OEM amplifier & speakers via AUX channel[cite: 2, 4]
* Retains climate control / AC overlay and factory vehicle info displays[cite: 1, 4]

---

## Operational Notes
* **OS Discrepancy:** The system reports Android 7.0 (Nougat, API 24) at the kernel/firmware level, despite being sold and displayed in the Tablet Status menu as Android 9.0[cite: 4].
* **Accessing OEM Interface:** Long-press `NAV` / `MAP` / `BACK` on center console (or screen tap depending on MCU firmware)[cite: 1, 4].
* **Audio Source:** Car stereo head unit must be switched to **AUX** input to hear Android audio/navigation[cite: 1, 4].
* **Display Scaling:** If the OEM Lexus interface appears stretched, cropped, or black, verify CAN protocol `LY-Lexus-V206` and screen configuration in the Factory Menu (PIN `666666`)[cite: 3, 4].