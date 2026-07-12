#
# Weather — real current conditions + today's forecast from Open-Meteo
# (free, no API key, https://open-meteo.com). Default location is home
# (Worcester, MA — override with JARVIS_HOME_LAT / JARVIS_HOME_LON /
# JARVIS_HOME_PLACE in .env); any spoken place name is geocoded live via
# Open-Meteo's geocoding API. Everything reported comes from the API
# response — if the call fails, the tool says so instead of guessing.
#

import os

import aiohttp
from loguru import logger

from pipecat.adapters.schemas.function_schema import FunctionSchema
from pipecat.services.llm_service import FunctionCallParams

def _env_float(name: str, default: float) -> float:
    """A typo'd .env value must not crash the whole bot at import time."""
    try:
        return float(os.getenv(name) or default)
    except ValueError:
        logger.warning(f"{name} in .env is not a number — using default {default}")
        return default


HOME_LAT = _env_float("JARVIS_HOME_LAT", 42.2626)
HOME_LON = _env_float("JARVIS_HOME_LON", -71.8023)
HOME_PLACE = os.getenv("JARVIS_HOME_PLACE", "Worcester, Massachusetts")

_TIMEOUT = aiohttp.ClientTimeout(total=10)

# WMO weather interpretation codes -> spoken-friendly text.
_WMO = {
    0: "clear skies",
    1: "mostly clear",
    2: "partly cloudy",
    3: "overcast",
    45: "fog",
    48: "freezing fog",
    51: "light drizzle",
    53: "drizzle",
    55: "heavy drizzle",
    56: "freezing drizzle",
    57: "heavy freezing drizzle",
    61: "light rain",
    63: "rain",
    65: "heavy rain",
    66: "freezing rain",
    67: "heavy freezing rain",
    71: "light snow",
    73: "snow",
    75: "heavy snow",
    77: "snow grains",
    80: "light showers",
    81: "showers",
    82: "violent showers",
    85: "snow showers",
    86: "heavy snow showers",
    95: "a thunderstorm",
    96: "a thunderstorm with hail",
    99: "a severe thunderstorm with hail",
}

GET_WEATHER_SCHEMA = FunctionSchema(
    name="get_weather",
    description=(
        "Get real current weather and today's forecast. With no arguments it uses the "
        "user's home location; pass a place name for anywhere else. Use whenever the "
        "user asks about weather, what to wear, or whether to work outside."
    ),
    properties={
        "place": {
            "type": "string",
            "description": "Optional city or town, e.g. 'Boston' or 'Worcester MA'. Empty for home.",
        }
    },
    required=[],
)


async def _geocode_search(session: aiohttp.ClientSession, name: str) -> list[dict]:
    async with session.get(
        "https://geocoding-api.open-meteo.com/v1/search",
        params={"name": name, "count": 1, "language": "en", "format": "json"},
    ) as resp:
        return (await resp.json()).get("results") or []


async def _geocode(session: aiohttp.ClientSession, place: str) -> tuple[float, float, str]:
    results = await _geocode_search(session, place)
    # Open-Meteo's geocoder only accepts a bare place name — "Worcester, Massachusetts"
    # or "Boston, MA" return ZERO results. Models routinely pass "City, State"
    # (it's how the home label reads), which used to fail the whole lookup and
    # send the voice model into an apologetic ramble. Retry with just the city.
    if not results and "," in place:
        results = await _geocode_search(session, place.split(",", 1)[0].strip())
    if not results:
        raise ValueError(f"no place called {place!r} was found")
    r = results[0]
    label = ", ".join(p for p in (r.get("name"), r.get("admin1")) if p)
    return float(r["latitude"]), float(r["longitude"]), label


async def fetch_weather(place: str = "") -> dict:
    async with aiohttp.ClientSession(timeout=_TIMEOUT) as session:
        if place.strip():
            lat, lon, label = await _geocode(session, place.strip())
        else:
            lat, lon, label = HOME_LAT, HOME_LON, HOME_PLACE

        async with session.get(
            "https://api.open-meteo.com/v1/forecast",
            params={
                "latitude": lat,
                "longitude": lon,
                "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
                "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
                "temperature_unit": "fahrenheit",
                "wind_speed_unit": "mph",
                "timezone": "auto",
                "forecast_days": 1,
            },
        ) as resp:
            if resp.status != 200:
                raise RuntimeError(f"weather service returned HTTP {resp.status}")
            data = await resp.json()

    cur = data["current"]
    day = data["daily"]
    return {
        "ok": True,
        "place": label,
        "now": {
            "temp_f": round(cur["temperature_2m"]),
            "feels_like_f": round(cur["apparent_temperature"]),
            "conditions": _WMO.get(cur["weather_code"], "unknown conditions"),
            "wind_mph": round(cur["wind_speed_10m"]),
        },
        "today": {
            "high_f": round(day["temperature_2m_max"][0]),
            "low_f": round(day["temperature_2m_min"][0]),
            "conditions": _WMO.get(day["weather_code"][0], "unknown conditions"),
            "precip_chance_pct": day["precipitation_probability_max"][0],
        },
    }


async def handle_get_weather(params: FunctionCallParams):
    place = str(params.arguments.get("place", "") or "")
    # Models sometimes pass the schema's words back literally ("home") —
    # geocoding that would land on some random town actually named Home.
    if place.strip().lower() in {"home", "here", "local", "my location", "current location"}:
        place = ""
    try:
        result = await fetch_weather(place)
    except Exception as e:
        result = {"ok": False, "error": f"weather lookup failed: {e}"}
    logger.info(f"get_weather(place={place!r}) -> ok={result['ok']}")
    await params.result_callback(result)
