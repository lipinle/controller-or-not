# Controller or Not

แอป Android ที่เปลี่ยนมือถือเป็นจอยเกม Bluetooth LE (HID over GATT) พร้อมหน้าจอจอยแบบสัมผัส
(analog ซ้าย/ขวา, D-pad, A/B/X/Y, LB/RB, LT/RT, View/Menu/Xbox, L3/R3) และรับสั่นจากเกมได้

## วิธีทำงาน

- มือถือเปิดเป็น **BLE peripheral** ประกาศ HID service (0x1812) + Device Information (0x180A) + Battery
- HID report map และ PnP ID (VID/PID) ทำให้เหมือน Xbox Wireless Controller:
  โปรไฟล์ `Xbox Series` (045E:0B13), `Xbox One S` (045E:02FD) หรือ `Generic` (ไม่เลียนแบบ)
- ใช้ GATT server แทน `BluetoothHidDevice` เพราะ API นั้นตั้ง VID/PID ไม่ได้ ส่วน GATT ตั้ง PnP ID เองได้

## ติดตั้ง

1. push ขึ้น GitHub แล้วโหลด APK จาก Actions → Build APK → Artifacts
   (หรือ `gradle assembleDebug` เองบนเครื่องที่มี Android SDK)
2. Android 8.0+ และต้องรองรับ BLE advertising (เครื่องส่วนใหญ่ได้)

## ใช้กับ iPad

1. เปิดแอป เลือกโปรไฟล์ `Xbox Series` กด **Start** (อนุญาต Bluetooth)
2. iPad: Settings → Bluetooth → เลือก "Xbox Wireless Controller" → Pair
3. เปิด Minecraft Bedrock ควรขึ้นการตั้งค่าคอนโทรลเลอร์ให้ใช้ได้
4. ถ้าเคยจับคู่แล้วพัง: กด Forget ทั้งฝั่ง iPad และลบอุปกรณ์ที่จับคู่ในฝั่ง Android แล้วลองใหม่

## ข้อจำกัดที่ต้องรู้ (ยังไม่ได้ทดสอบกับ iPad จริง)

- **iOS เลือกรับเฉพาะจอยที่ตัวเองรู้จัก** (MFi / Xbox / PlayStation / Switch Pro) จอย HID ทั่วไปมักไม่ขึ้นเป็นคอนโทรลเลอร์ในเกม
  การเลียนแบบ PnP ID + descriptor ของ Xbox เป็นแนวทางที่มีรายงานว่าบางกรณีใช้ได้ แต่ **ไม่มีการรับประกัน**
  และ Apple เปลี่ยนพฤติกรรมได้ทุกเวอร์ชัน
- Android ตั้ง Appearance (0x03C4 gamepad) ในโฆษณาไม่ได้ ซึ่งจอยจริงมี อาจมีผลกับ iOS
- ไม่มี Share button / battery report แบบจอยจริง (Share ส่งเป็น 0 เสมอ)
- โค้ดนี้ยังไม่เคยถูกคอมไพล์หรือรันบนเครื่องจริง (สภาพแวดล้อมที่เขียนไม่มี Android SDK) ให้ดู build ของ GitHub Actions ก่อน
- ถ้า iPad ไม่จับเป็นจอย ให้ลองโปรไฟล์ `Xbox One S` สลับกัน หรือทดสอบกับ Android/PC ก่อนเพื่อแยกว่าปัญหาอยู่ฝั่งไหน
