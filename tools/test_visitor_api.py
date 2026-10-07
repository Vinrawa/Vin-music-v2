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
print("Got visitor_id:", visitor_id)

# Now test web remix player request with visitor_id
payload = {
    "context": {
        "client": {
            "clientName": "WEB_REMIX",
            "clientVersion": "1.20240801.01.00",
            "visitorData": visitor_id,
            "hl": "en",
            "gl": "IN"
        }
    },
    "videoId": "Iy-dJwHVX84",
    "playbackContext": {
        "contentPlaybackContext": {
            "html5Preference": "HTML5_PREF_WANTS",
            "signatureTimestamp": 19999
        }
    }
}

req = urllib.request.Request(
    "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
    data=json.dumps(payload).encode("utf-8"),
    headers={
        "Content-Type": "application/json",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36",
        "X-YouTube-Client-Name": "67",
        "X-YouTube-Client-Version": "1.20240801.01.00",
        "X-Goog-Visitor-Id": visitor_id,
        "Origin": "https://music.youtube.com",
        "Referer": "https://music.youtube.com/"
    }
)

with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode("utf-8"))
    print("WEB_REMIX status:", data.get("playabilityStatus", {}).get("status"))
    print("WEB_REMIX reason:", data.get("playabilityStatus", {}).get("reason"))
    sd = data.get("streamingData", {})
    formats = sd.get("adaptiveFormats", []) or sd.get("formats", [])
    print("Formats count:", len(formats))
    for f in formats[:3]:
        print("Format:", f.get("mimeType"), "URL:", bool(f.get("url")), "Cipher:", bool(f.get("signatureCipher")))
