# Phone as Controller — Xbox 360 imitatsiyasi

Android telefonni kompyuter uchun virtual Xbox 360 gamepadiga aylantiradi. Telefondagi
tugmalar bosilishi UDP orqali PC'ga yuboriladi, PC tarafdagi Python server esa ViGEmBus
drayveri yordamida haqiqiy Xbox 360 kontrolleri sifatida ko'rsatadi — ya'ni o'yinlar uni
oddiy gamepad deb qabul qiladi.

## Tarkibi

| Qism | Joyi | Vazifasi |
| --- | --- | --- |
| Android klient | [app/](app/) | Jetpack Compose'da yozilgan gamepad UI, UDP paket yuboradi |
| PC server | [server.py](server.py) | UDP paketni qabul qilib, virtual Xbox 360 kontrollerini boshqaradi |

## Imkoniyatlar

- Sozlanadigan layout: tugmalarni surib joylashtirish, o'lchamini o'zgartirish, qo'shish/o'chirish (Edit rejimi)
- Layout va oxirgi IP manzil DataStore'da saqlanadi
- Ikki analog stik, D-Pad, action tugmalar, L1/R1 va analog bo'lmagan L2/R2 triggerlar
- Akselerometr rejimi — telefonni egib chap stikni boshqarish
- ~66 Hz (15 ms) tezlikda holat yuborish

## Protokol

Har bir paket 8 bayt, big-endian, format `>HBBBBBB`:

```
[buttons: uint16][LX][LY][RX][RY][L2][R2]   // stik/trigger baytlari 0..255, markaz = 128
```

Port: `5005/udp`.

## Ishga tushirish

### 1. PC (server)

ViGEmBus drayverini o'rnating: https://github.com/ViGEm/ViGEmBus/releases

```bash
pip install vgamepad
python server.py
```

Server konsolda kompyuter IP manzilini ko'rsatadi.

### 2. Telefon (klient)

Android Studio'da loyihani oching va ilovani o'rnating, yoki:

```bash
./gradlew installDebug
```

Ilovani ochib, serverda ko'rsatilgan IP'ni kiriting va **CONNECT** tugmasini bosing.

Telefon va kompyuter bir xil tarmoqda bo'lishi kerak (yoki Radmin/Hamachi kabi VPN orqali).

## Talablar

- Android 8.0+ (minSdk 26), compileSdk 35, JDK 17
- Windows + ViGEmBus (server faqat Windows'da virtual kontroller yarata oladi)
- Python 3.8+
