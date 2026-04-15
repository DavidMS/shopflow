import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const server = new McpServer({
  name: "weather-server",
  version: "1.0.0",
  description: "Consulta el tiempo actual de cualquier ciudad usando Open-Meteo",
});

// Herramienta: obtener el tiempo actual de una ciudad
server.tool(
  "get_current_weather",
  {
    city: z.string().describe("Nombre de la ciudad (en español o inglés)"),
  },
  async ({ city }) => {
    try {
      // Paso 1: Geocodificar la ciudad → obtener latitud/longitud
      const geocodeUrl =
        `https://geocoding-api.open-meteo.com/v1/search` +
        `?name=${encodeURIComponent(city)}&count=1&language=es&format=json`;
      const geocodeRes = await fetch(geocodeUrl);
      const geocodeData = await geocodeRes.json();

      if (!geocodeData.results?.length) {
        return {
          content: [{
            type: "text",
            text: `No se encontró la ciudad: "${city}". Prueba con el nombre en inglés.`,
          }],
        };
      }

      const { latitude, longitude, name, country, timezone } = geocodeData.results[0];

      // Paso 2: Consultar el tiempo actual con las coordenadas
      const weatherUrl =
        `https://api.open-meteo.com/v1/forecast` +
        `?latitude=${latitude}&longitude=${longitude}` +
        `&current=temperature_2m,apparent_temperature,relative_humidity_2m,` +
        `wind_speed_10m,precipitation,weather_code` +
        `&timezone=${encodeURIComponent(timezone)}`;

      const weatherRes = await fetch(weatherUrl);
      const weatherData = await weatherRes.json();
      const c = weatherData.current;

      const result = [
        `Tiempo actual en ${name}, ${country}`,
        `─────────────────────────────────────`,
        `Temperatura:    ${c.temperature_2m}°C (sensación ${c.apparent_temperature}°C)`,
        `Humedad:        ${c.relative_humidity_2m}%`,
        `Viento:         ${c.wind_speed_10m} km/h`,
        `Precipitación:  ${c.precipitation} mm`,
        `Estado:         ${describeWeatherCode(c.weather_code)}`,
        `Hora local:     ${c.time}`,
      ].join("\n");

      return { content: [{ type: "text", text: result }] };

    } catch (error) {
      return {
        content: [{ type: "text", text: `Error consultando el tiempo: ${error.message}` }],
      };
    }
  }
);

// Mapa simplificado de códigos WMO (https://open-meteo.com/en/docs)
function describeWeatherCode(code) {
  const descriptions = {
    0: "Despejado",
    1: "Principalmente despejado", 2: "Parcialmente nublado", 3: "Nublado",
    45: "Niebla", 48: "Niebla con escarcha",
    51: "Llovizna ligera", 53: "Llovizna moderada", 55: "Llovizna densa",
    61: "Lluvia ligera", 63: "Lluvia moderada", 65: "Lluvia fuerte",
    71: "Nevada ligera", 73: "Nevada moderada", 75: "Nevada intensa",
    80: "Chubascos ligeros", 81: "Chubascos moderados", 82: "Chubascos violentos",
    95: "Tormenta eléctrica", 99: "Tormenta con granizo",
  };
  return descriptions[code] ?? `Código WMO ${code}`;
}

// Arrancar el servidor con transporte stdio (estándar para MCP local)
const transport = new StdioServerTransport();
await server.connect(transport);