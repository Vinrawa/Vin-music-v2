import json
import urllib.request

video_id = "Iy-dJwHVX84"

# Let's test IOS with minimal payload
payload = {
    "context": {
        "client": {
            "clientName": "IOS",
            "clientVersion": "19.29.1",
            "deviceMake": "Apple",
            "deviceModel": "iPhone16,2",
            "hl": "en",
            "gl": "US"
        }
    },
    "videoId": video_id,
    "racyCheckOk": True,
    "contentCheckOk": True
}

headers = {
    "Content-Type": "application/json",
    "User-Agent": "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X)",
    "X-YouTube-Client-Name": "5",
    "X-YouTube-Client-Version": "19.29.1"
}

req = urllib.request.Request(
    "https://www.youtube.com/youtubei/v1/player",
    data=json.dumps(payload).encode("utf-8"),
    headers=headers
)

try:
    with urllib.request.urlopen(req) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        print("IOS status:", res.get("playabilityStatus", {}).get("status"))
        sd = res.get("streamingData", {})
        formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
        urls = [f.get("url") for f in formats if f.get("url") and "audio" in f.get("mimeType", "")]
        print(f"Direct Audio URLs: {len(urls)}")
        if urls:
            print("First URL:", urls[0][:80])
except urllib.error.HTTPError as e:
    print(f"HTTP Error {e.code}: {e.read().decode('utf-8', errors='ignore')}")
except Exception as e:
    print("Error:", e)
