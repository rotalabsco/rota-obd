# Formato dos dados gravados

Tudo fica na pasta do app no armazenamento externo (`Android/data/co.rotalabs.obd/files/`) e vai junto
no `.zip` de "Exportar sessões".

## Sessões: `sessoes/sessao_AAAA-MM-DD_HH-mm-ss.ndjson`

Uma linha JSON por observação. Campos comuns:

| Campo | Significado |
|---|---|
| `t_ms` | milissegundos desde o início da sessão (monotônico) |
| `ts` | data e hora em milissegundos desde 1970 |
| `kind` | tipo da linha (abaixo) |
| `mod` | módulo endereçado (`7E0` motor, `7E1` câmbio...) |
| `req` | pedido enviado (`010C`, `2101`, `03`...) |
| `status` | `ok`, `negative`, `no_data`, `timeout`, `error` |
| `resp` | resposta em hexadecimal (só com `ok`) |
| `nrc`, `nrc_meaning` | código e significado da resposta negativa |

Tipos (`kind`): `session`, `adapter`, `probe`, `modules`, `supported`, `pid` (com `name`, `unit`,
`value`), `vehicle_info`, `dtc`, `dtc_decoded` (com `codes`), `lid_sweep`, `lid_sweep_aborted`,
`lid_watch`, `clear_dtc`, `raw`, `coleta`.

`coleta` marca o início e o fim de cada passo da Coleta guiada: `event` (`inicio` ou `fim`), `step` (id do
passo, em `core/GuidedCollection.kt`), `title` no início e `status` no fim (`concluído`, `interrompido` ou
`gravado`, este para a gravação da viagem, que não tem duração).

Regra: falha de leitura nunca vira zero. Se não houve resposta, a linha diz isso no `status`.

Exemplo:
```json
{"t_ms":1532,"ts":1763049000123,"kind":"pid","mod":"7E0","req":"010C","status":"ok","resp":"410C1AF8","name":"Rotação","unit":"rpm","value":1726}
{"t_ms":9810,"ts":1763049008401,"kind":"lid_sweep","mod":"7E0","req":"2105","status":"negative","nrc":"31","nrc_meaning":"identificador fora da faixa"}
```

## Relatórios: `sessoes/relatorio_<timestamp>.md`

Texto gerado pela Descoberta, o mesmo de "Compartilhar relatório". Quando a Descoberta é um passo da Coleta guiada, o nome
leva o id do passo: `relatorio_<timestamp>_<passo>.md`.

## Garagem: `garagem.json`

```json
{
  "version": 1,
  "vehicle": {"name": "...", "vin": "...", "calibration": "...", "plate": "...", "fuel": "Diesel",
              "tankLiters": 88, "initialOdometerKm": 140000},
  "fuel": [{"id": "a1b2c3d4", "date": 1763049000000, "odometerKm": 148920, "liters": 71.3,
            "totalPrice": 456.32, "fullTank": true, "station": "...", "note": ""}],
  "items": [{"id": "...", "name": "Óleo e filtro do motor", "intervalKm": 10000, "intervalMonths": 12, "note": ""}],
  "records": [{"id": "...", "itemId": "...", "date": 1763049000000, "odometerKm": 148000, "cost": 650, "description": "..."}],
  "lastFuelLevelPct": 42.5,
  "lastFuelLevelAt": 1763049000000
}
```

Consumo: calculado entre dois abastecimentos de tanque cheio, somando os litros de todos os
abastecimentos depois do primeiro até o segundo (inclusive os parciais).

## Vencimentos: `vencimentos.json`

```json
{
  "version": 1,
  "deadlines": [{"id": "...", "name": "IPVA", "due": 1768003200000, "repeatMonths": 12,
                 "warnDaysBefore": 15, "amount": 3180, "note": "cota única"}]
}
```

`repeatMonths` nulo significa que não se repete (ao marcar como pago, a data é limpa).

## Gravação segura

`garagem.json` e `vencimentos.json` são gravados num arquivo temporário e renomeados. Se um arquivo
estiver corrompido ao abrir, ele é copiado para `*.corrompido-<timestamp>` e o app recomeça vazio, sem
perder o original.

## Exportação (.zip)

Organizada por categoria (`core/ExportLayout.kt`):

| Pasta | Conteúdo |
|---|---|
| `coleta/NN-passo/` | as linhas de cada passo da Coleta guiada, recortadas das sessões pelos marcadores `coleta`, e o relatório quando o passo é uma Descoberta |
| `descobertas/` | relatórios de Descobertas feitas fora da Coleta guiada |
| `sessoes-completas/` | as sessões inteiras, sem recorte |
| `garagem/` | `garagem.json` e `vencimentos.json` |
| `LEIA-ME.txt` | o que tem em cada pasta e a lista dos passos gravados, com o status |

Nada é apagado automaticamente: os arquivos ficam na central até o app ser desinstalado.
