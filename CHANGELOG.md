# Changelog

Todos los cambios notables de **Latido** se documentan en este archivo.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/)
y el proyecto usa [Versionado Semántico](https://semver.org/lang/es/).

## [Sin publicar]

### Por hacer
- Reconocer el patrón SOS específico (3 golpes – pausa – 3 golpes).
- Servicio en primer plano para escaneo con pantalla apagada.
- Exportar el registro de detecciones (CSV) para relevo entre rescatistas.

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

[Sin publicar]: https://github.com/filmxora/latido/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/filmxora/latido/releases/tag/v1.0.0
