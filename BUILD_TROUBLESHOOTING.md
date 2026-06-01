# APK Build Issue - Solution Guide

## 🔴 Problem
Android Gradle build is failing with out-of-memory errors due to **insufficient Windows virtual memory (paging file)**.

Error: "The paging file is too small for this operation to complete"

## ✅ Solution - Choose ONE:

### Option 1: Increase Virtual Memory (RECOMMENDED - 5 mins)
1. Right-click `increase_virtual_memory.bat` → "Run as administrator"
2. Press Enter when prompted
3. **RESTART your computer** (required!)
4. After restart, try building again: `.\gradlew assembleDebug`

### Option 2: Manual Windows Setting
1. Right-click "This PC" or "My Computer" → Properties
2. Click "Advanced system settings"
3. Performance section → Click "Settings..."
4. "Advanced" tab → "Virtual memory" → "Change..."
5. Set paging file to:
   - Initial size: 8192 MB
   - Maximum size: 16384 MB
6. Click "Set" → OK → Restart

### Option 3: Close Background Apps (Quick Temporary Fix)
1. Close VS Code, Chrome, Discord, etc. (free up RAM)
2. Try: `.\gradlew assembleDebug --no-daemon`

### Option 4: Build on Different Machine
- Use a computer with more available RAM
- Or use GitHub Actions (cloud-based builds)

## 📱 After Successful Build
APK will be at: `app\build\outputs\apk\debug\app-debug.apk`

Install to phone:
```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Or copy APK manually via USB and install.

---

## ℹ️ Technical Details
- Java Gradle daemon needs ~2GB memory
- Windows paging file is swapping disk space that acts as extra RAM
- Your system shows only ~6GB free RAM out of 16GB total
- Multiple VS Code instances + other apps = memory pressure
- Solution: Increase disk-based virtual memory to compensate

Good luck! 🚀
