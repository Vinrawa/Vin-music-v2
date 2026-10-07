/**
 * Decode Backend Proxy — Firebase Cloud Function
 *
 * This function proxies AI decode requests to Groq, keeping the API key
 * server-side so it's never exposed in the Android APK.
 *
 * Setup:
 * 1. npm install firebase-functions groq-sdk
 * 2. firebase functions:secrets:set GROQ_API_KEY
 * 3. firebase deploy --only functions
 */

const { onRequest } = require("firebase-functions/v2/https");
const Groq = require("groq-sdk");

// Rate limiting: simple in-memory tracker (resets on cold start)
const ipCounts = new Map();
const MAX_REQUESTS_PER_HOUR = 30;

const DECODE_SYSTEM_PROMPT = `You are Decode — a music historian and cultural analyst.
Provide deep, structured explanations of songs in 4 layers:

1. LITERAL — What the lyrics say plainly
2. CONTEXT — Slang, references, artist biography, cultural references, samples
3. DEEPER — Metaphors, double entendres, hidden meanings, artist intent
4. PRODUCTION — Producers, writers, samples used, recording stories

Respond ONLY with valid JSON matching this exact schema:
{
  "trackKey": "<title|artist lowercase>",
  "title": "<song title>",
  "author": "<artist name>",
  "literal": "<literal explanation paragraph>",
  "context": "<context explanation paragraph>",
  "deeper": "<deeper meaning explanation paragraph>",
  "production": "<production details paragraph>",
  "samples": [{"sourceTrack": "<sampled song>", "sourceArtist": "<original artist>", "type": "sample|cover|remix|interpolation"}],
  "references": ["<cultural reference 1>", "<cultural reference 2>"]
}

Rules:
- Be specific. Name names, dates, places.
- Hinglish OK if Indian artist.
- If lyrics are unavailable, focus on what you know about the song from other context.
- If you don't know something, say so honestly rather than fabricating.
- Keep each section to 2-4 paragraphs max.
- samples array can be empty if no known samples.
- references array should list 3-8 cultural/historical references mentioned or alluded to.`;

exports.generateDecode = onRequest(
  {
    cors: true,
    secrets: ["GROQ_API_KEY"],
    maxInstances: 10,
    timeoutSeconds: 60,
  },
  async (req, res) => {
    // Only POST
    if (req.method !== "POST") {
      res.status(405).json({ error: "Method not allowed" });
      return;
    }

    // Simple IP rate limiting (per-instance; resets on cold start)
    const ip = req.ip || "unknown";
    const now = Date.now();
    const hourAgo = now - 3600000;

    // Evict stale IPs so the tracker Map can't grow without bound on
    // long-lived instances.
    if (ipCounts.size > 5000) {
      for (const [key, ts] of ipCounts) {
        const fresh = ts.filter((t) => t > hourAgo);
        if (fresh.length === 0) {
          ipCounts.delete(key);
        } else {
          ipCounts.set(key, fresh);
        }
      }
    }

    if (!ipCounts.has(ip)) {
      ipCounts.set(ip, []);
    }
    const timestamps = ipCounts.get(ip).filter((t) => t > hourAgo);
    if (timestamps.length >= MAX_REQUESTS_PER_HOUR) {
      res.status(429).json({ error: "Rate limit exceeded. Try again later." });
      return;
    }
    timestamps.push(now);
    ipCounts.set(ip, timestamps);

    // Clamp every client-supplied field before it reaches the prompt — these
    // used to be interpolated verbatim, so megabyte bodies could be fed into
    // paid LLM calls until context overflow.
    const clampText = (value, max = 20000) =>
      typeof value === "string" ? value.slice(0, max) : null;
    const clampList = (value, maxItems = 20, maxEach = 200) =>
      Array.isArray(value)
        ? value
            .filter((item) => typeof item === "string")
            .slice(0, maxItems)
            .map((item) => item.slice(0, maxEach))
        : null;

    const title = clampText(req.body.title, 300);
    const author = clampText(req.body.author, 300);
    const album = clampText(req.body.album, 300);
    const lyrics = clampText(req.body.lyrics, 60000);
    const musicBrainzInfo = clampText(req.body.musicBrainzInfo);
    const lastFmTags = clampList(req.body.lastFmTags);
    const lastFmWiki = clampText(req.body.lastFmWiki);
    const wikiSummary = clampText(req.body.wikiSummary);
    const samples = clampList(req.body.samples);

    if (!title || !author) {
      res.status(400).json({ error: "title and author are required" });
      return;
    }

    const prompt = `Song: ${title} by ${author}
Album: ${album || "Unknown"}

Lyrics: ${lyrics || "Not available"}

MusicBrainz: ${musicBrainzInfo || "N/A"}
Last.fm Tags: ${lastFmTags ? lastFmTags.join(", ") : "N/A"}
Last.fm Wiki: ${lastFmWiki || "N/A"}
Wikipedia: ${wikiSummary || "N/A"}
Samples: ${samples ? samples.join(", ") : "N/A"}

Generate full decode.`;

    try {
      const groq = new Groq({ apiKey: process.env.GROQ_API_KEY });

      const response = await groq.chat.completions.create({
        messages: [
          { role: "system", content: DECODE_SYSTEM_PROMPT },
          { role: "user", content: prompt },
        ],
        model: "llama-3.3-70b-versatile",
        temperature: 0.3,
        max_tokens: 2000,
        response_format: { type: "json_object" },
      });

      const content = response.choices[0]?.message?.content;
      if (!content) {
        res.status(500).json({ error: "Empty response from AI" });
        return;
      }

      const decoded = JSON.parse(content);

      // Ensure required fields exist
      const result = {
        trackKey:
          decoded.trackKey ||
          `${title.toLowerCase().trim()}|${author.toLowerCase().trim()}`,
        title: decoded.title || title,
        author: decoded.author || author,
        literal: decoded.literal || "",
        context: decoded.context || "",
        deeper: decoded.deeper || "",
        production: decoded.production || "",
        samples: Array.isArray(decoded.samples) ? decoded.samples : [],
        references: Array.isArray(decoded.references)
          ? decoded.references
          : [],
        generatedAt: Date.now(),
      };

      res.json(result);
    } catch (error) {
      console.error("Decode generation error:", error);
      res.status(500).json({ error: "Failed to generate decode" });
    }
  }
);
