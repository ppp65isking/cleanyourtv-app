package tv.cleanyour.core.flows

// The choosable interface (0.2): CLASSIC is the 0.1 black-and-glass design exactly as shipped;
// LIQUID_GLASS is the same layout and tokens with liquid-glass surfaces (a moving specular
// sheen on glass, a stronger ambient glow, a glass top bar). The choice is a style, not a
// theme: colours, type and spacing do not change, so CLASSIC renders as 0.1 did. Persisted in
// settings.txt as the enum name, so an unknown value falls back to CLASSIC.
enum class UiStyle { CLASSIC, LIQUID_GLASS }
