# Documentação do Rota.OBD

Tudo o que serviu de base para o projeto e o que é preciso saber para continuar o trabalho.

| Documento | Para que serve |
|---|---|
| [referencias.md](referencias.md) | Projetos, artigos e normas consultados, com o que foi aproveitado de cada um e a licença |
| [protocolo.md](protocolo.md) | ELM327, respostas negativas, serviço 21 da Mitsubishi e cálculo de consumo em diesel |
| [arquitetura.md](arquitetura.md) | Como o código está organizado e como os fluxos funcionam |
| [modo-de-espera.md](modo-de-espera.md) | Como o app convive com o modo de espera do adaptador sem drenar a bateria |
| [formato-dos-dados.md](formato-dos-dados.md) | Arquivos gravados: sessões NDJSON, `garagem.json`, `vencimentos.json` |
| [veiculo-pajero-4m41.md](veiculo-pajero-4m41.md) | O que já se sabe do carro de referência e o plano de testes |
| [decisoes.md](decisoes.md) | Decisões de projeto e por que foram tomadas |
| [build-e-ci.md](build-e-ci.md) | Como compilar, rodar os testes e gerar o APK automaticamente |
| [visual.md](visual.md) | Visual do app: RotaLabs DS, tokens, fontes e regras para uso no carro |

Perfis de veículo ficam em [`../profiles/`](../profiles/README.md).
