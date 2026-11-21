namespace Spatia.Core.Models;

/// <summary>Per-output safety envelope, read from the shared engine so both apps use identical limits.</summary>
public sealed record OutputSafety(float MaxBoostDb, float LimiterCeilingDb, float VolumeWarningFraction, bool IsHeadphones);

public sealed record AutoEqResult(bool Ok, string? Error, float PreampDb, IReadOnlyList<EqFilter> Filters, IReadOnlyList<string> Warnings);
