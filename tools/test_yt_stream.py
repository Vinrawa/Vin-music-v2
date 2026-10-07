import json
import urllib.request
import urllib.error

video_id = "Iy-dJwHVX84"

clients = [
    {
        "name": "ANDROID_MUSIC",
        "client": {
            "clientName": "ANDROID_MUSIC",
            "clientVersion": "7.02.51",
            "androidSdkVersion": 32,
            "hl": "en",
            "gl": "IN"
        },
        "headers": {
            "User-Agent": "com.google.android.apps.youtube.music/7.02.51 (Linux; U; Android 12; en_US)",
            "X-YouTube-Client-Name": "21",
            "X-YouTube-Client-Version": "7.02.51"
        }
    },
    {
        "name": "IOS",
        "client": {
            "clientName": "IOS",
            "clientVersion": "19.29.1",
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "osName": "iPhone",
            "osVersion": "17.5.1.21F90",
            "hl": "en",
            "gl": "IN"
        },
        "headers": {
            "User-Agent": "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X) AppleWebKit/605.1.15",
            "X-YouTube-Client-Name": "5",
            "X-YouTube-Client-Version": "19.29.1"
        }
    },
    {
        "name": "IOS_MUSIC",
        "client": {
            "clientName": "IOS",
            "clientVersion": "19.28.1",
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "osName": "iPhone",
            "osVersion": "17.5.1.21F90",
            "hl": "en",
            "gl": "IN"
        },
        "headers": {
            "User-Agent": "com.google.ios.youtubemusic/7.02.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
            "X-YouTube-Client-Name": "26",
            "X-YouTube-Client-Version": "7.02.1"
        }
    },
    {
        "name": "ANDROID_CREATOR",
        "client": {
            "clientName": "ANDROID_CREATOR",
            "clientVersion": "23.01.100",
            "androidSdkVersion": 32,
            "hl": "en",
            "gl": "IN"
        },
        "headers": {
            "User-Agent": "com.google.android.apps.youtube.creator/23.01.100 (Linux; U; Android 12; en_US)",
            "X-YouTube-Client-Name": "62",
            "X-YouTube-Client-Version": "23.01.100"
        }
    },
    {
        "name": "WEB_REMIX",
        "client": {
            "clientName": "WEB_REMIX",
            "clientVersion": "1.20240401.01.00",
            "hl": "en",
            "gl": "IN"
        },
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
            "X-YouTube-Client-Name": "67",
            "X-YouTube-Client-Version": "1.20240401.01.00",
            "Origin": "https://music.youtube.com",
            "Referer": "https://music.youtube.com/"
        }
    },
    {
        "name": "TVHTML5",
        "client": {
            "clientName": "TVHTML5",
            "clientVersion": "7.20240401.08.00",
            "hl": "en",
            "gl": "IN"
        },
        "headers": {
            "User-Agent": "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            "X-YouTube-Client-Name": "7",
            "X-YouTube-Client-Version": "7.20240401.08.00",
            "Origin": "https://www.youtube.com"
        }
    }
]

# Fetch visitor data first
req = urllib.request.Request(
    "https://www.youtube.com/",
    headers={"User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36"}
)
visitor_data = ""
try:
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode("utf-8", errors="ignore")
        import re
        m = re.search(r'"VISITOR_DATA":"([^"]+)"', html)
        if m:
            visitor_data = m.group(1)
            print(f"Got visitor data: {visitor_data[:20]}...")
except Exception as e:
    print("Visitor error:", e)

for c in clients:
    payload = {
        "context": {
            "client": c["client"]
        },
        "videoId": video_id,
        "contentCheckOk": True,
        "racyCheckOk": True
    }
    if visitor_data:
        payload["context"]["client"]["visitorData"] = visitor_data
    
    headers = {
        "Content-Type": "application/json",
        **c["headers"]
    }
    if visitor_data:
        headers["X-Goog-Visitor-Id"] = visitor_data
        
    req = urllib.request.Request(
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        data=json.dumps(payload).encode("utf-8"),
        headers=headers
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("playabilityStatus", {}).get("status")
            reason = data.get("playabilityStatus", {}).get("reason")
            sd = data.get("streamingData", {})
            formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
            audio_urls = [f.get("url") for f in formats if f.get("url") and "audio" in f.get("mimeType", "")]
            print(f"[{c['name']}] Status: {status}, Reason: {reason}, Audio URLs found: {len(audio_urls)}")
            if audio_urls:
                print(f"  --> Sample URL: {audio_urls[0][:80]}...")
    except urllib.error.HTTPError as e:
        print(f"[{c['name']}] HTTP Error: {e.code} - {e.read().decode('utf-8', errors='ignore')[:150]}")
    except Exception as e:
        print(f"[{c['name']}] Error: {e}")
