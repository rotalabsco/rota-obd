# Visual

O Rota.OBD usa o **RotaLabs DS v2**, o design system da [RotaLabs](https://rotalabs.co), no tema escuro. O
desenho das telas foi feito antes do código, num canvas com as sete telas em 1280×720 (a tela da central
ADAK LINK8S).

## Onde está no código

| Arquivo | O que tem |
|---|---|
| `ui/theme/Theme.kt` | Tokens de cor (`Rl`), fontes (`RlFonts`), estilos próprios (`RlType`) e o mapeamento para o Material 3 |
| `ui/theme/Icons.kt` | Ícones de traço no estilo Lucide, desenhados como caminhos SVG |
| `ui/Components.kt` | Botões, quadro de leitura (`MetricTile`), selos, abas em pílula, cartões |
| `res/font/` | Bricolage Grotesque, Geist e Geist Mono (OFL; licenças em `assets/licencas/`) |
| `res/drawable/rotalabs_mark.xml` | Símbolo da RotaLabs, também usado no ícone do app |

Os nomes dos tokens seguem o DS: `bg`, `raised`, `sunken`, `line`, `text`, `text2`, `text3`, `orange`,
`accent`, `success`, `warning`, `danger` e as variantes `-soft` e `-line` para fundos e bordas de alerta.

## Regras para uso no carro

- Números em Geist Mono, formato brasileiro (`1.840 rpm`, `88,0 °C`). A formatação fica em `core/LiveMetrics.kt`
  (`Formato`), com testes.
- Alvo de toque de pelo menos 56 dp nos botões e 72 dp no menu lateral.
- **Falha de leitura nunca vira zero:** o quadro mostra "—" com borda tracejada e o motivo (sem dados, tempo
  esgotado, resposta não decodificada).
- Laranja só no botão principal, no item ativo do menu e em destaques; o resto é neutro.
- Cor de estado sempre acompanhada de rótulo ("Vencida", "Acima de 105 °C"), nunca só a cor.
- Nada de tamanho fixo em pixels de tela: pesos e grades adaptáveis, para caber em qualquer densidade.

## Alertas padrão

Definidos em `LiveMetrics.limits`, conservadores para motor diesel e ajustáveis quando houver perfil do
veículo:

| PID | Leitura | Atenção | Perigo |
|---|---|---|---|
| 05 | Temperatura do líquido de arrefecimento | 105 °C | 112 °C |
| 5C | Temperatura do óleo | 120 °C | 130 °C |

A temperatura do fluido do câmbio só entra quando o perfil do serviço 21 estiver validado pela calibração.

## O que ainda não está no app

- Controle de música no painel flutuante (o painel em si já existe: tela Conexão → "Painel por cima dos apps").
- Tema claro do DS: os tokens existem no DS, mas o app só usa o escuro por enquanto.
