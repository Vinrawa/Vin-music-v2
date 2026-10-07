import json
import urllib.request

video_id = "Iy-dJwHVX84"

# Let's call /youtubei/v1/visitor_id endpoint
req = urllib.request.Request(
    "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false",
    data=json.dumps({"context": {"client": {"clientName": "WEB", "clientVersion": "2.20240801.01.00"}}}).encode("utf-8"),
    headers={"Content-Type": "application/json"}
)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode("utf-8"))
    visitor_id = data.get("responseContext", {}).get("visitorData")
    print("Fetched visitorData from API:", visitor_id)

# Now test ANDROID_VR with this visitorData and with clientScreen=WATCH
payload = {
    "context": {
        "client": {
            "clientName": "ANDROID_VR",
            "clientVersion": "1.60.19",
            "deviceMake": "Oculus",
            "deviceModel": "Quest 3",
            "androidSdkVersion": 32,
            "visitorData": visitor_id,
            "hl": "en",
            "gl": "US"
        },
        "user": {
            "lockedSafetyMode": False
        }
    },
    "videoId": video_id,
    "racyCheckOk": True,
    "contentCheckOk": True
}

req = urllib.request.Request(
    "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Content-Type": "application/json",
        "User-Agent": "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
        "X-YouTube-Client-Name": "28",
        "X-YouTube-Client-Version": "1.60.19",
        "X-Goog-Visitor-Id": visitor_id
    }
)

try:
    with urllib.request.urlopen(req) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        print("ANDROID_VR Playability Status:", res.get("playabilityStatus", {}).get("status"))
        print("ANDROID_VR Reason:", res.get("playabilityStatus", {}).get("reason"))
        sd = res.get("streamingData", {})
        formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
        urls = [f.get("url") for f in formats if f.get("url") and "audio" in f.get("mimeType", "")]
        print(f"Direct Audio URLs found: {len(urls)}")
        if urls:
            print("First URL:", urls[0][:80])
except Exception as e:
    print("Error:", e)
