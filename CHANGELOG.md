# Changelog

Todos los cambios notables de **Latido** se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto usa [Versionado Semántico](https://semver.org/lang/es/).

## [Sin publicar]

### Por hacer
- Marca de tiempo y geolocalización opcional en el CSV exportado.

## [1.3.0] - 2026-06-25

### Corregido (precisión de detección)
- **Falsos impactos al iniciar**: ahora hay una fase de **calibración real** (~1.8 s, con
  asentamiento de 0.4 s) que mide el ruido ambiental antes de detectar. Estado "Calibrando…".
- **Umbral degenerado ≈ 0**: se añade un **piso físico** a la desviación del ruido
  (0.15 m/s²), de modo que un teléfono inmóvil ya no dispara con cualquier microvibración.
- **Slider invertido**: la dirección y las etiquetas eran contradictorias (derecha decía
  "más sensible" pero subía σ = menos sensible). Ahora **derecha = más sensible**, sin ambigüedad.
- **Realimentación de la alarma**: detección por **flanco de subida con histéresis** (un golpe
  = un impacto) + **pausa de detección mientras suena la alarma** + filtro de **ritmo humano**
  (periodo ≈0.25–2.5 s), para que la vibración del propio teléfono/máquina no se confirme como
  señal de vida.

### Cambiado
- El estado de impacto ya no se queda "pegado": se considera reciente solo ~1.5 s tras el
  último golpe. Nuevo estado de UI "Calibrando…".

## [1.2.0] - 2026-06-25

### Añadido
- **Servicio en primer plano** con WakeLock parcial: el escaneo continúa con la
  pantalla apagada o la app en segundo plano, con notificación persistente.
- **Ventana emergente (diálogo) + alarma sonora en bucle** al confirmar una señal
  de vida, además de la vibración. La alarma usa el canal de alarma del sistema
  (suena aunque el teléfono esté en silencio). Botón "SILENCIAR" con rearmado a los 8 s.
- **Exportar registro a CSV** compartible (correo, WhatsApp, Drive…) vía FileProvider,
  con hora, magnitud, tipo de patrón y confianza, para el relevo entre rescatistas.

### Cambiado
- La lógica de sensor/alarma se trasladó del ViewModel a `ScanService`; el ViewModel
  quedó como fachada delgada sobre el estado compartido.
- El análisis de ritmo usa ahora el reloj monótono del sensor de forma consistente
  (se eliminó la mezcla con `System.currentTimeMillis()`).
- El registro conserva hasta 200 entradas (antes 50) y guarda patrón y confianza.

## [1.1.0] - 2026-06-25

### Añadido
- **Detección de patrón en grupos** (tipo auxilio: "golpe-golpe-golpe, pausa, …"):
  separa los impactos en ráfagas y confirma tamaños de grupo y pausas consistentes,
  elevando la confianza y mostrando alerta específica "golpes en grupos (auxilio)".
- El registro y el banner ahora distinguen patrón rítmico simple vs. patrón en grupos.

## [1.0.0] - 2026-06-25

### Añadido
- Muestreo del acelerómetro a alta frecuencia (`SENSOR_DELAY_FASTEST`).
- Aceleración lineal vía `TYPE_LINEAR_ACCELERATION`, con filtro paso-alto de respaldo.
- Umbral **adaptativo** al ruido ambiental (`media + k·σ`) con sensibilidad ajustable.
- Detección de impactos con periodo refractario (anti-eco).
- Análisis de **ritmo** por regularidad de intervalos → alerta "posible señal de vida".
- UI Compose: osciloscopio en vivo, banner de estado, métricas y registro de patrones.
- Vibración de alerta y pantalla siempre encendida durante el escaneo.
- Icono adaptativo y nombre de la app: **Latido**.

[Sin publicar]: https://github.com/filmxora/latido/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/filmxora/latido/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/filmxora/latido/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/filmxora/latido/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/filmxora/latido/releases/tag/v1.0.0
