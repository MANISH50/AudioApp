namespace Spatia.Core.Logic;

/// <summary>Why playback failed; each value has a user-facing message (PlayerError_*) in the strings file.</summary>
public enum PlayerError { None, Decoder, Unsupported, FileAccess, ExclusiveRefused, Device }
