# Notas de protocolo

## Adaptador ELM327

- Configuração usada: `ATZ`, `ATE0` (sem eco), `ATL0`, `ATS0` (sem espaços), `ATH0` (sem cabeçalhos),
  `ATCAF1` (formatação CAN), `ATAT1`, `ATSP6` (CAN 11 bits / 500 kbps; cai para `ATSP0` se falhar).
- A resposta termina no prompt `>`. Em BLE, uma notificação não é uma resposta: acumule até o `>`.
- Multiquadro ISO-TP chega como linha de tamanho (`014`) seguida de `0:`, `1:`...
- Endereçamento por módulo: `ATSH 7E0` + `ATCRA 7E8` (motor), `ATSH 7E1` + `ATCRA 7E9` (câmbio).
  Volta ao broadcast com `ATSH 7DF` + `ATAR`.

## Respostas negativas (7F serviço código)

| Código | Significado | O que indica |
|---|---|---|
| 11 | serviço não suportado | protocolo errado |
| 12 | subfunção não suportada | serviço certo, tamanho de identificador errado |
| 31 | fora da faixa | serviço certo, esse identificador não existe |
| 33 | acesso negado | precisa de desbloqueio (não fazemos) |
| 78 | resposta pendente | aguarde a próxima linha |

## Mitsubishi

- Dados do fabricante pelo serviço `21` com identificador de 1 byte (00 a FF).
- As posições dos bytes mudam por versão de software da ECU: confirme sempre pela calibração (modo 09 PID 04).
- Pajero Full 3.2 DI-D (4M41) 2013/14: motor confirmado em CAN 11 bits / 500 kbps. Resto a descobrir.

## Consumo em motor diesel

A conta pelo MAF (usada em gasolina) não vale para diesel. Caminhos, em ordem de preferência:
1. PID padrão `5E` (vazão de combustível), se a ECU suportar.
2. Volume injetado por ciclo (mm³/curso) lido no serviço 21:
   `L/h = mm³_por_curso × cilindros × (rpm / 2) × 60 / 1.000.000`
3. Calibração com tanques cheios para corrigir o fator.
