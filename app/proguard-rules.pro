# Règles de réduction du code (R8).
# MapLibre utilise du code natif appelé par réflexion.
-keep class org.maplibre.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class fr.pushpull.champimap.**$$serializer { *; }
-keepclassmembers class fr.pushpull.champimap.** { *** Companion; }
