package co.rotalabs.obd.core

/**
 * Coleta guiada: o roteiro de teste no carro (docs/roteiro-servico-21.md) dentro do app.
 *
 * Cada passo diz em português o que fazer, roda a leitura sozinho e grava no arquivo da sessão uma
 * linha `coleta` no início e outra no fim. Assim a análise sabe o que estava acontecendo em cada
 * leitura sem anotação à mão, e a exportação separa os dados por passo.
 */
object GuidedCollection {

    /** Uma instrução que aparece na tela a partir de [atSec] segundos do início do passo. */
    data class Cue(val atSec: Int, val text: String)

    /** Uma leitura repetida: identificador do serviço 21 (`21xx`) ou PID padrão (`01xx`). */
    data class Read(val module: Module, val service: Int, val id: Int) {
        val hex: String get() = "%02X%02X".format(service, id)
    }

    sealed interface Action {
        /**
         * A Descoberta, com o serviço 21. [full]: varre os 256 identificadores de cada módulo. Sem ele,
         * relê só os que já responderam numa varredura completa (bem mais rápido). A varredura completa
         * fica no começo (motor desligado) e com o motor quente, para não perder um identificador que
         * só responda com o motor ligado.
         */
        data class Discovery(val full: Boolean = false) : Action

        /**
         * Lê [reads] em rodízio por [durationSec] segundos, ou até parar, se null. [rotateKnown]: a cada
         * volta, lê também esse tanto de LIDs da última Descoberta completa, um pedaço por volta, até
         * passar por todos (as fixas a cada ~3 s, as do rodízio a cada ~1 min).
         */
        data class Watch(val reads: List<Read>, val durationSec: Int?, val cues: List<Cue>, val rotateKnown: Int = 0) : Action

        /** Passo sem leitura: a pessoa faz e marca como feito. */
        data object Manual : Action
    }

    data class Step(
        val id: String,
        val group: String,
        val title: String,
        val howTo: String,
        val action: Action,
    )

    const val GROUP_BEFORE = "Antes de sair · carro parado, motor frio"
    const val GROUP_TRIP = "Na viagem"
    const val GROUP_ARRIVAL = "Na chegada · motor quente, carro parado"
    const val GROUP_SCREEN = "Tela da central"

    private val E = Modules.ENGINE
    private val T = Modules.TRANSMISSION
    private fun lid(m: Module, id: Int) = Read(m, 0x21, id)
    private fun pid(id: Int) = Read(E, 0x01, id)

    private val RPM = pid(0x0C)
    private val COOLANT = pid(0x05)
    private val SPEED = pid(0x0D)
    private val LOAD = pid(0x04)

    private val gearCues = listOf(
        Cue(0, "Pé firme no freio. Alavanca em P"),
        Cue(10, "Passe para R"),
        Cue(20, "Passe para N"),
        Cue(30, "Passe para D, sem tirar o pé do freio"),
        Cue(45, "Volte para P"),
    )

    /**
     * Roteiro enxuto: o que dá para fazer de verdade antes de sair, sem ficar parado segurando
     * rotação. O grosso dos dados vem da viagem, que lê em rodízio todos os LIDs que responderam.
     */
    val steps: List<Step> = listOf(
        Step(
            "d1-ignicao", GROUP_BEFORE, "Ignição ligada, motor desligado",
            "Gire a chave até acender o painel, sem dar partida. Toque em Começar e espere terminar (uns 2 minutos).",
            Action.Discovery(full = true),
        ),
        Step(
            "d2-frio-lenta", GROUP_BEFORE, "Motor ligado, parado",
            "Dê a partida e deixe em marcha lenta, sem acelerar. Toque em Começar e espere terminar (uns 30 segundos).",
            Action.Discovery(),
        ),
        Step(
            "cambio-alavanca-a", GROUP_BEFORE, "Câmbio: P, R, N, D (opcional)",
            "Se der: carro parado, freio de mão puxado e pé firme no freio o tempo todo; ninguém na frente nem atrás do carro. " +
                "Siga as trocas que aparecem na tela (1 minuto). Se não der, pule para a viagem.",
            Action.Watch(listOf(lid(T, 0x05), lid(T, 0x08)), 60, gearCues),
        ),
        Step(
            "viagem", GROUP_TRIP, "Gravar a viagem",
            "Toque em Começar antes de sair e dirija normalmente. Pode abrir o Maps ou a música: a gravação continua em segundo plano " +
                "(aparece uma notificação do Rota.OBD). Ninguém precisa mexer na central. " +
                "Para sozinho quando o motor fica desligado por 2 minutos; depois de uma parada, toque em Começar de novo.",
            Action.Watch(
                listOf(lid(T, 0x08), lid(T, 0x02), lid(E, 0x01), lid(E, 0x02), RPM, SPEED, COOLANT, LOAD),
                null,
                listOf(Cue(0, "Pode dirigir. Não precisa mexer em nada")),
                rotateKnown = 8,
            ),
        ),
        Step(
            "d6-quente-lenta", GROUP_ARRIVAL, "Motor quente, parado",
            "Na chegada, ainda com o motor ligado e quente, em P. Toque em Começar e espere terminar (uns 2 minutos).",
            Action.Discovery(full = true),
        ),
        Step(
            "tela-central", GROUP_SCREEN, "Densidade e prints da tela",
            "Carro pode estar desligado. Configurações do Android → Sobre → toque 7 vezes em \"Número da versão\". " +
                "Depois, Opções do desenvolvedor → \"Largura mínima\": anote o número. Tire prints do Ao vivo, Falhas, " +
                "Garagem e Conexão. Marque como feito quando terminar.",
            Action.Manual,
        ),
    )

    fun step(id: String): Step? = steps.firstOrNull { it.id == id }

    /** Instrução vigente em [elapsedSec]: a última cujo início já passou. */
    fun cueAt(cues: List<Cue>, elapsedSec: Int): Cue? = cues.lastOrNull { it.atSec <= elapsedSec }

    /** Próxima instrução depois de [elapsedSec], para avisar o que vem. */
    fun nextCue(cues: List<Cue>, elapsedSec: Int): Cue? = cues.firstOrNull { it.atSec > elapsedSec }

    /** Primeiro passo não feito depois de [currentId], para sugerir o próximo. */
    fun nextPending(currentId: String?, done: Set<String>): Step? {
        val start = steps.indexOfFirst { it.id == currentId } + 1
        return (steps.drop(start) + steps.take(start)).firstOrNull { it.id !in done }
    }
}
