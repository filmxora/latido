# Latido — Detector sísmico de señales de vida

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requisitos-del-dispositivo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF?logo=kotlin&logoColor=white)](#)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-UI-4285F4?logo=jetpackcompose&logoColor=white)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Latido** es una app Android nativa (Kotlin + Jetpack Compose) que convierte el teléfono
en un detector sísmico de contacto para rescate en estructuras colapsadas. Lee el acelerómetro a alta
frecuencia, aísla la aceleración lineal (sin gravedad), calcula un **umbral adaptativo**
al ruido ambiental y confirma **patrones rítmicos** (golpes de auxilio) frente al ruido.

## Cómo se detecta una señal de vida
1. **Aceleración lineal**: usa `TYPE_LINEAR_ACCELERATION` (gravedad ya removida por el
   sistema) o, si no existe, estima la gravedad con un filtro paso-bajo y la resta.
2. **Umbral adaptativo**: `umbral = media_ruido + k·σ`, calculado solo con muestras
   tranquilas, así los golpes no inflan el propio umbral. `k` es ajustable (slider).
3. **Detección de impactos** con periodo refractario (evita ecos/rebotes).
4. **Análisis de ritmo**: mide la regularidad de los últimos impactos (coeficiente de
   variación de los intervalos). Si es regular → "POSIBLE SEÑAL DE VIDA" + vibración.

## Compilar el APK

### Opción A — Android Studio (recomendado)
1. Abre **Android Studio** (Ladybug o superior).
2. `File > Open` y selecciona esta carpeta `SismografoRescate`.
3. Espera el *Gradle sync* (descarga el wrapper y las dependencias).
4. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`.
5. El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
6. Para instalar en el teléfono: `Run` con el dispositivo conectado por USB
   (con *Depuración USB* activada), o copia el APK e instálalo manualmente.

### Opción B — Línea de comandos
Necesitas el **Android SDK** y una variable `ANDROID_HOME` (o un `local.properties`
con `sdk.dir=/ruta/al/Android/sdk`). Si no tienes el Gradle wrapper (`gradlew`), genéralo:

```bash
# Con Gradle instalado (brew install gradle):
cd SismografoRescate
gradle wrapper --gradle-version 8.10.2

# Compilar APK de debug:
./gradlew assembleDebug

# Instalar en un dispositivo conectado:
./gradlew installDebug
```

## Requisitos del dispositivo
- Android 8.0 (API 26) o superior.
- Acelerómetro (prácticamente todos lo tienen).
- El permiso `HIGH_SAMPLING_RATE_SENSORS` (declarado en el manifiesto) permite muestrear
  por encima de 200 Hz en Android 12+. Es un permiso normal, no requiere diálogo.

## Uso en campo
1. Activa **modo silencio** y apoya el teléfono **en contacto directo** con la viga o losa.
2. Pulsa **INICIAR ESCANEO** y mantén el teléfono inmóvil (deja ~2 s de calibración).
3. Ajusta la sensibilidad si hay mucho ruido (maquinaria): súbela hacia "menos sensible".
4. La pantalla se mantiene encendida; el teléfono vibra al detectar un patrón rítmico.

## ⚠️ Aviso importante
Es una **herramienta de apoyo**, NO sustituye equipo profesional de búsqueda
(geófonos / dispositivos sísmico-acústicos como Delsar). El acelerómetro de un móvil
detecta bien por **contacto directo o muy cercano**, no a metros de distancia. Valida
siempre con los protocolos del cuerpo de rescate y evita generar falsas esperanzas.
