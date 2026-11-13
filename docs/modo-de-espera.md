# Modo de espera do adaptador

Adaptadores como o Vgate iCar Pro 2S entram em espera sozinhos quando o carro fica parado, para não
drenar a bateria. O app não pode atrapalhar isso.

## O problema

Cada pedido OBD gera tráfego na rede CAN. Esse tráfego acorda e mantém acordados o adaptador e as
centrais eletrônicas do carro. Um app que consulta sem parar, mesmo com o motor desligado, impede o
modo de espera e consome bateria.

## Como o app decide

O app lê a tensão com `ATRV`. Esse comando é respondido pelo próprio adaptador e **não gera tráfego no
carro**. A tensão indica se o motor está ligado:

- acima de ~13,0 V: alternador carregando, motor ligado;
- ~12,6 V ou menos: motor desligado.

A regra está em `core/SleepPolicy.kt` e é testada em `SleepAndDeadlinesTest`:

| Situação | Modo | O que o app faz |
|---|---|---|
| Motor ligado | Ativo | Funciona normalmente |
| Motor desligado há 2 min | Silencioso | Pausa Ao vivo e Explorar 21; lembra de retomar o Ao vivo |
| Motor desligado há 7 min, ou 3 leituras de tensão sem resposta | Liberar | Fecha a conexão para o adaptador dormir |
| Carro ligou de novo | — | Reconecta sozinho (a cada 20 a 60 s) e retoma o Ao vivo se ele estava aberto |

Detalhes:
- O vigia lê a tensão a cada 30 s no modo ativo e a cada 60 s no silencioso.
- Toda ação pedida pelo usuário (descoberta, ler falhas, explorar) conta como atividade e reinicia o
  prazo, para não interromper algo feito de propósito com a ignição ligada e o motor desligado.
- Se o adaptador cair por conta própria (entrou em espera), o app passa a aguardar o carro e reconecta.
- Ao abrir o app, ele conecta sozinho ao último adaptador usado, se a permissão de Bluetooth já foi dada.
- "Desconectar" na tela de conexão esquece o adaptador e para as tentativas.
- O modo demonstração não usa o vigia.

## Ajustes possíveis

Os limites (13,0 V, 2 min, 7 min) são parâmetros do construtor de `SleepPolicy`. Carros com sistema
start-stop ou alternador inteligente podem precisar de outro limite de tensão.
