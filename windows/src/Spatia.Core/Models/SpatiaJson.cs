using System.Text.Json;
using System.Text.Json.Serialization;

namespace Spatia.Core.Models;

/// <summary>JSON settings matching the Kotlin engine: camelCase properties, SNAKE_CASE_UPPER enum names.</summary>
public static class SpatiaJson
{
    public static readonly JsonSerializerOptions Options = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.SnakeCaseUpper, allowIntegerValues: false) },
        WriteIndented = false,
        MaxDepth = 16,
        // NaN/Infinity are passed to the engine, whose validation replaces them with safe defaults.
        NumberHandling = JsonNumberHandling.AllowNamedFloatingPointLiterals,
    };

    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);

    public static T? Deserialize<T>(string json) => JsonSerializer.Deserialize<T>(json, Options);
}
