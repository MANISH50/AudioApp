namespace Spatia.Core.Models;

// Names must match the Kotlin engine's enums. JSON uses SNAKE_CASE_UPPER (see SpatiaJson).

public enum Mode { Music, Movie, Gaming, Voice, Night, Custom }

public enum OutputType { Speaker, Wired, Bluetooth, Usb, HdmiCast }

public enum Environment { Off, Hall, Cinema, Park, Bathroom, Echoing, Cathedral, Stadium, Club, LivingRoom, Tunnel }

public enum FilterType { Peak, LowShelf, HighShelf, LowPass, HighPass, BandPass }
