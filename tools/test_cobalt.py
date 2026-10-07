import json
import urllib.request
import urllib.error

video_ids = ["kJQP7kiw5Fk", "fJ9rUzIMcZQ", "dQw4w9WgXcQ", "Iy-dJwHVX84"]

# Let's test TV client:
# TVHTML5 client with clientVersion "7.20230405.08.01" or "7.20240401.08.00"
# TV_EMBED with thirdParty
# ANDROID_VR with deviceModel Quest 2 / Quest 3

def test_stream(vid):
    print(f"\n================ Testing Video: {vid} ================")
    # 1. TV client with user-agent
    payload = {
        "context": {
            "client": {
                "clientName": "TVHTML5",
                "clientVersion": "7.20230405.08.01",
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": vid,
        "contentCheckOk": True,
        "racyCheckOk": True
    }
    req = urllib.request.Request(
        "https://www.youtube.com/youtubei/v1/player",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "User-Agent": "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/23.lts.4-qa (unlike Gecko) v8/8.8.278.8-profiling Starboard/14, TV_2023/1.0 (Sony, BRAVIA 4K VH2, Wireless)",
            "X-YouTube-Client-Name": "7",
            "X-YouTube-Client-Version": "7.20230405.08.01"
        }
    )
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            status = data.get("playabilityStatus", {}).get("status")
            reason = data.get("playabilityStatus", {}).get("reason")
            sd = data.get("streamingData", {})
            formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
            audio = [f for f in formats if "audio" in f.get("mimeType", "") and f.get("url")]
            print(f"[TVHTML5 Cobalt] status={status}, reason={reason}, audio_streams={len(audio)}")
            if audio:
                print("  URL:", audio[0]["url"][:80])
    except Exception as e:
        print("[TVHTML5 Cobalt] Error:", e)

    # 2. WEB_CREATOR or ANDROID_CREATOR
    payload = {
        "context": {
            "client": {
                "clientName": "ANDROID",
                "clientVersion": "19.29.35",
                "androidSdkVersion": 30,
                "hl": "en",
                "gl": "US"
            }
        },
        "videoId": vid,
        "contentCheckOk": True,
        "racyCheckOk": True
    }

for v in video_ids:
    test_stream(v)
