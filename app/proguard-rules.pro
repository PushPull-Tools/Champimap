# Règles de réduction du code (R8).
# MapLibre utilise du code natif appelé par réflexion.
-keep class org.maplibre.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class fr.pushpull.champimap.**$$serializer { *; }
-keepclassmembers class fr.pushpull.champimap.** { *** Companion; }
# Sécurité supplémentaire (release minifiée) : on garde l'accès aux sérialiseurs générés
# des classes @Serializable de l'appli (partage de spot, sortie à plusieurs, espèces, carnet).
-keepclassmembers @kotlinx.serialization.Serializable class fr.pushpull.champimap.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
