board : Arduino UNO Q

## Power

VCC and GND are shared.

- HX711 VCC -> 5V
- HX711 GND -> GND
- Reed switch VCC -> 3.3V
- Reed switch GND -> GND

## Signal pins

Use only D5 through D13 for sensor signals.

| Sensor | Module pin | UNO Q pin |
| --- | --- | --- |
| HX711 1 | DT | D5 |
| HX711 1 | SCK | D6 |
| HX711 2 | DT | D7 |
| HX711 2 | SCK | D8 |
| HX711 3 | DT | D12 |
| HX711 3 | SCK | D13 |
| Reed switch 1 | DO | D9 |
| Reed switch 2 | DO | D10 |
| Reed switch 3 | DO | D11 |

## Connection summary

- HX711 1: VCC 5V, GND common, DT D5, SCK D6
- HX711 2: VCC 5V, GND common, DT D7, SCK D8
- HX711 3: VCC 5V, GND common, DT D12, SCK D13
- Reed switch 1: VCC 3.3V, GND common, DO D9
- Reed switch 2: VCC 3.3V, GND common, DO D10
- Reed switch 3: VCC 3.3V, GND common, DO D11
