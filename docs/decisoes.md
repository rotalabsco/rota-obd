# Decisões de projeto

Registro curto das escolhas feitas e do motivo, para quem continuar o projeto.

| # | Decisão | Motivo |
|---|---|---|
| 1 | App próprio em vez de seguir no Car Scanner | Recursos foram ficando pagos e o app não dá acesso aos dados brutos nem aos dados do fabricante |
| 2 | Kotlin + Jetpack Compose, um módulo, sem bibliotecas além de AndroidX e coroutines | Simples de compilar, de manter e de contribuir; menos risco de quebrar com atualizações |
| 3 | Protocolo escrito do zero, sem usar kotlin-obd-api | As bibliotecas existentes não cobrem o serviço 21 nem o endereçamento por módulo |
| 4 | Licença MIT; AndrOBD (GPL) só como referência | Permite que qualquer um reuse o código, inclusive em outros apps |
| 5 | Somente leitura; a única escrita é apagar códigos (modo 04) com confirmação | Escrita errada na ECU pode danificar o carro |
| 6 | Falha de leitura nunca vira zero | Dados falsos são piores que dados ausentes |
| 7 | Endereçar cada pedido ao módulo (`ATSH` + `ATCRA`) | Vários módulos respondem a um broadcast e misturar respostas inventa dados |
| 8 | Dados do fabricante por perfis JSON validados pela calibração | Posições dos bytes mudam por versão da ECU; um perfil errado gera números confiantes e falsos |
| 9 | Gravação em NDJSON, uma observação por linha | Fácil de analisar, de juntar e de enviar para outras ferramentas |
| 10 | Garagem e vencimentos em JSON local, sem banco de dados | Volume pequeno, fácil de exportar e sincronizar, sem plugin de compilação extra |
| 11 | Respeitar o modo de espera do adaptador pela tensão (`ATRV`) | Ler a tensão não gera tráfego no carro; consultar sem parar impede o adaptador de dormir |
| 12 | Não fazer skin da ADAK; usar painel flutuante e, opcionalmente, modo tela inicial | Skins são fechadas, mudam por firmware e não servem para a comunidade; não é possível embutir Maps ou Waze em outro app |
| 13 | Música pelo sistema de mídia do Android (MediaSession) | Controla YouTube Music ou Spotify sem abrir o app deles |
| 14 | Exportação: resumo para o Home Assistant por MQTT; arquivos brutos por pasta sincronizada (Syncthing) | O HA cria sensores sozinho; Syncthing não exige nuvem nem login dentro do app |
| 15 | APK gerado pelo GitHub Actions e publicado em Releases | Quem testa não precisa de Android Studio; erros de build viram issue automaticamente |

## Próximos passos combinados

1. Primeiro teste no carro (Descoberta) e ajuste do que aparecer.
2. Temperatura do câmbio e do motor com alerta, e viagens automáticas com consumo real.
3. Painel flutuante com controle de música.
4. Sincronização com o Home Assistant.
5. Rodada de visual: ícones, hierarquia no Ao vivo, números no formato brasileiro, cores de alerta.
6. Importar o histórico do Carango, se ele exportar arquivo.
