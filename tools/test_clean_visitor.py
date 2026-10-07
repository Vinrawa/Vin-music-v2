import json
import urllib.request

def get_visitor_id():
    req = urllib.request.Request(
        "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
        data=json.dumps({"context": {"client": {"clientName": "WEB", "clientVersion": "2.20240801.01.00"}}}).encode("utf-8"),
        headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        return data.get("responseContext", {}).get("visitorData")

visitor_id = get_visitor_id()
print("Clean visitor_id:", visitor_id)

test_clients = [
    {
        "name": "ANDROID_VR",
        "url": "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        "client": {
            "clientName": "ANDROID_VR",
            "clientVersion": "1.60.19",
            "deviceMake": "oculus",
            "deviceModel": "Quest 3",
            "androidSdkVersion": 32,
            "hl": "en",
            "gl": "IN",
            "visitorData": visitor_id
        },
        "headers": {
            "User-Agent": "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
            "X-YouTube-Client-Name": "28",
            "X-YouTube-Client-Version": "1.60.19",
            "X-Goog-Visitor-Id": visitor_id
        }
    },
    {
        "name": "ANDROID_TESTSUITE",
        "url": "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        "client": {
            "clientName": "ANDROID_TESTSUITE",
            "clientVersion": "1.9",
            "androidSdkVersion": 30,
            "hl": "en",
            "gl": "IN",
            "visitorData": visitor_id
        },
        "headers": {
            "User-Agent": "com.google.android.youtube.testsuite/1.9 (Linux; U; Android 11; en_US)",
            "X-YouTube-Client-Name": "89",
            "X-YouTube-Client-Version": "1.9",
            "X-Goog-Visitor-Id": visitor_id
        }
    },
    {
        "name": "ANDROID_EMBEDDED",
        "url": "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
        "client": {
            "clientName": "ANDROID_EMBEDDED_PLAYER",
            "clientVersion": "19.29.35",
            "androidSdkVersion": 34,
            "hl": "en",
            "gl": "IN",
            "visitorData": visitor_id
        },
        "extra": {
            "thirdParty": {"embedUrl": "https://www.youtube.com"}
        },
        "headers": {
            "User-Agent": "com.google.android.youtube/19.29.35 (Linux; U; Android 14; en_US) gzip",
            "X-YouTube-Client-Name": "55",
            "X-YouTube-Client-Version": "19.29.35",
            "X-Goog-Visitor-Id": visitor_id
        }
    }
]

for tc in test_clients:
    payload = {
        "context": {
            "client": tc["client"],
            **tc.get("extra", {})
        },
        "videoId": "Iy-dJwHVX84",
        "racyCheckOk": True,
        "contentCheckOk": True
    }
    req = urllib.request.Request(
        tc["url"],
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **tc["headers"]}
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("playabilityStatus", {}).get("status")
            reason = data.get("playabilityStatus", {}).get("reason")
            sd = data.get("streamingData", {})
            formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
            audio = [f for f in formats if "audio" in f.get("mimeType", "") and f.get("url")]
            print(f"[{tc['name']}] status={status}, reason={reason}, audio_urls={len(audio)}")
            if audio:
                print("  URL:", audio[0]["url"][:80])
    except Exception as e:
        print(f"[{tc['name']}] Error: {e}")
