# Perfis de veículo

Cada perfil descreve onde estão os dados do fabricante para um carro: módulo, serviço, identificador,
posição do byte e fórmula. Um perfil só vale para as calibrações (modo 09 PID 04) que confirmou.

Formato (rascunho, vai evoluir com o primeiro perfil validado):

```json
{
  "id": "mitsubishi-pajero-full-4m41",
  "match": { "vin_prefix": ["JMYLYV"], "calibration": [] },
  "signals": [
    { "name": "Volume injetado", "module": "7E0", "request": "21XX", "byte": 0, "length": 1,
      "formula": "A * 0.25", "unit": "mm³/curso", "verified": false }
  ]
}
```

Regra: só marque `verified: true` o que foi conferido no carro, com o valor batendo com outra fonte.
