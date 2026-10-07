import json
import urllib.request
import urllib.error

video_id = "Iy-dJwHVX84"

# Let's test different configurations of clients
test_configs = [
    # 1. IOS with proper userAgent in client context
    {
        "name": "IOS_v19",
        "client": {
            "clientName": "IOS",
            "clientVersion": "19.29.1",
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "osName": "iOS",
            "osVersion": "17.5.1.21F90",
            "hl": "en",
            "gl": "US",
            "utcOffsetMinutes": 0
        },
        "headers": {
            "User-Agent": "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
            "X-YouTube-Client-Name": "5",
            "X-YouTube-Client-Version": "19.29.1",
            "X-Goog-Api-Format-Version": "2"
        }
    },
    # 2. IOS 19.45.4
    {
        "name": "IOS_v19_45",
        "client": {
            "clientName": "IOS",
            "clientVersion": "19.45.4",
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "osName": "iOS",
            "osVersion": "18.1.0.22B83",
            "hl": "en",
            "gl": "US"
        },
        "headers": {
            "User-Agent": "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1 like Mac OS X)",
            "X-YouTube-Client-Name": "5",
            "X-YouTube-Client-Version": "19.45.4"
        }
    },
    # 3. ANDROID with clientVersion 19.29.35
    {
        "name": "ANDROID_OFFICIAL",
        "client": {
            "clientName": "ANDROID",
            "clientVersion": "19.29.35",
            "androidSdkVersion": 34,
            "hl": "en",
            "gl": "US"
        },
        "headers": {
            "User-Agent": "com.google.android.youtube/19.29.35 (Linux; U; Android 14; en_US) gzip",
            "X-YouTube-Client-Name": "1",
            "X-YouTube-Client-Version": "19.29.35"
        }
    },
    # 4. MWEB (Mobile Web)
    {
        "name": "MWEB",
        "client": {
            "clientName": "MWEB",
            "clientVersion": "2.20240801.01.00",
            "hl": "en",
            "gl": "US"
        },
        "headers": {
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            "X-YouTube-Client-Name": "2",
            "X-YouTube-Client-Version": "2.20240801.01.00",
            "Origin": "https://m.youtube.com"
        }
    },
    # 5. TV_EMBEDDED
    {
        "name": "TV_EMBED",
        "client": {
            "clientName": "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            "clientVersion": "2.0",
            "hl": "en",
            "gl": "US"
        },
        "extra_body": {
            "thirdParty": {
                "embedUrl": "https://www.youtube.com"
            }
        },
        "headers": {
            "User-Agent": "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1",
            "X-YouTube-Client-Name": "85",
            "X-YouTube-Client-Version": "2.0",
            "Origin": "https://www.youtube.com",
            "Referer": "https://www.youtube.com/"
        }
    },
    # 6. WEB EMBEDDED
    {
        "name": "WEB_EMBED",
        "client": {
            "clientName": "WEB_EMBEDDED_PLAYER",
            "clientVersion": "1.20240801.01.00",
            "hl": "en",
            "gl": "US"
        },
        "extra_body": {
            "thirdParty": {
                "embedUrl": "https://www.youtube.com"
            }
        },
        "headers": {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
            "X-YouTube-Client-Name": "56",
            "X-YouTube-Client-Version": "1.20240801.01.00",
            "Origin": "https://www.youtube.com",
            "Referer": "https://www.youtube.com/"
        }
    }
]

for c in test_configs:
    payload = {
        "context": {
            "client": c["client"]
        },
        "videoId": video_id,
        "contentCheckOk": True,
        "racyCheckOk": True,
        "playbackContext": {
            "contentPlaybackContext": {
                "html5Preference": "HTML5_PREF_WANTS",
                "signatureTimestamp": 20000
            }
        }
    }
    if "extra_body" in c:
        payload["context"].update(c["extra_body"])
        
    req = urllib.request.Request(
        "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            **c["headers"]
        }
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("playabilityStatus", {}).get("status")
            reason = data.get("playabilityStatus", {}).get("reason")
            sd = data.get("streamingData", {})
            formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
            audio_urls = [f.get("url") for f in formats if f.get("url") and "audio" in f.get("mimeType", "")]
            cipher_urls = [f for f in formats if f.get("signatureCipher") or f.get("cipher")]
            print(f"[{c['name']}] Status: {status}, Reason: {reason}, Direct Audio: {len(audio_urls)}, Cipher: {len(cipher_urls)}")
            if audio_urls:
                print(f"  --> DIRECT URL: {audio_urls[0][:80]}...")
    except urllib.error.HTTPError as e:
        print(f"[{c['name']}] HTTP Error: {e.code} - {e.read().decode('utf-8', errors='ignore')[:150]}")
    except Exception as e:
        print(f"[{c['name']}] Error: {e}")
