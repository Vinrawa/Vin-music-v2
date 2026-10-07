import json
import urllib.request

video_id = "Iy-dJwHVX84"

def test_ios_payload(context_client, headers):
    payload = {
        "context": {
            "client": context_client
        },
        "videoId": video_id,
        "contentCheckOk": True,
        "racyCheckOk": True
    }
    req = urllib.request.Request(
        "https://www.youtube.com/youtubei/v1/player",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("playabilityStatus", {}).get("status")
            reason = data.get("playabilityStatus", {}).get("reason")
            sd = data.get("streamingData", {})
            formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
            audio = [f for f in formats if "audio" in f.get("mimeType", "") and f.get("url")]
            print(f"SUCCESS: status={status}, reason={reason}, audio_streams={len(audio)}")
            if audio:
                print("  Audio URL:", audio[0]["url"][:80])
            return True
    except urllib.error.HTTPError as e:
        print(f"FAIL ({e.code}): {e.read().decode('utf-8', errors='ignore')[:100]}")
        return False
    except Exception as e:
        print(f"ERR: {e}")
        return False

# Variation 1: Metrolist iOS
print("Test 1: Metrolist iOS")
test_ios_payload(
    {
        "clientName": "IOS",
        "clientVersion": "19.29.1",
        "deviceMake": "Apple",
        "deviceModel": "iPhone16,2",
        "osName": "iPhone",
        "osVersion": "17.5.1.21F90",
        "gl": "US",
        "hl": "en"
    },
    {
        "User-Agent": "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
        "X-YouTube-Client-Name": "5",
        "X-YouTube-Client-Version": "19.29.1",
        "Origin": "https://www.youtube.com"
    }
)

# Variation 2: NewPipe iOS
print("Test 2: NewPipe iOS")
test_ios_payload(
    {
        "clientName": "IOS",
        "clientVersion": "19.45.4",
        "deviceMake": "Apple",
        "deviceModel": "iPhone14,5",
        "osName": "iPhone",
        "osVersion": "18.1.1.22B91",
        "gl": "US",
        "hl": "en"
    },
    {
        "User-Agent": "com.google.ios.youtube/19.45.4 (iPhone14,5; U; CPU iOS 18_1_1 like Mac OS X)",
        "X-YouTube-Client-Name": "5",
        "X-YouTube-Client-Version": "19.45.4"
    }
)

# Variation 3: Android with userAgent
print("Test 3: Android Client 19.34.42")
test_ios_payload(
    {
        "clientName": "ANDROID",
        "clientVersion": "19.34.42",
        "androidSdkVersion": 34,
        "osName": "Android",
        "osVersion": "14",
        "gl": "US",
        "hl": "en"
    },
    {
        "User-Agent": "com.google.android.youtube/19.34.42 (Linux; U; Android 14; US) gzip",
        "X-YouTube-Client-Name": "1",
        "X-YouTube-Client-Version": "19.34.42"
    }
)

# Variation 4: Android Music with clientVersion 7.20.51
print("Test 4: Android Music 7.20.51")
test_ios_payload(
    {
        "clientName": "ANDROID_MUSIC",
        "clientVersion": "7.20.51",
        "androidSdkVersion": 34,
        "osName": "Android",
        "osVersion": "14",
        "gl": "US",
        "hl": "en"
    },
    {
        "User-Agent": "com.google.android.apps.youtube.music/7.20.51 (Linux; U; Android 14; US) gzip",
        "X-YouTube-Client-Name": "21",
        "X-YouTube-Client-Version": "7.20.51"
    }
)

# Variation 5: WEB with visitorData and signatureTimestamp
print("Test 5: WEB Client")
test_ios_payload(
    {
        "clientName": "WEB",
        "clientVersion": "2.20240901.00.00",
        "gl": "US",
        "hl": "en"
    },
    {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "X-YouTube-Client-Name": "1",
        "X-YouTube-Client-Version": "2.20240901.00.00",
        "Origin": "https://www.youtube.com",
        "Referer": "https://www.youtube.com/"
    }
)
