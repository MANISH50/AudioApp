using Spatia.Core.Models;

namespace Spatia.Core.Apo;

/// <summary>
/// What Equalizer APO has to do for one set of settings, computed by the shared engine (spatia_apo_plan). Matrices are
/// row-major 2x2: L' = m[0]*L + m[1]*R, R' = m[2]*L + m[3]*R.
/// </summary>
public sealed record ApoPlan(
    bool Bypass,
    float PreampDb,
    IReadOnlyList<EqFilter> Filters,
    IReadOnlyList<float> PreMatrix,
    IReadOnlyList<float> PostMatrix,
    bool NeedsImpulse,
    IReadOnlyList<string> Approximations)
{
    /// <summary>Approximation ids (match the engine's ApoPlanner.APPROX_*).</summary>
    public const string ApproxNight = "night", ApproxNormalization = "normalization", ApproxPsychoBass = "psychoBass",
        ApproxSpatial = "spatial", ApproxCrossfeed = "crossfeed";
}
