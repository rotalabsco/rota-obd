package co.rotalabs.obd.core

/**
 * Descrição dos códigos de falha em português.
 *
 * A tabela só traz códigos genéricos (SAE J2012), com o significado que vale para qualquer carro.
 * Códigos do fabricante e os que não estão aqui recebem apenas o que a própria estrutura do código
 * garante (sistema, origem e grupo), sem inventar significado: uma descrição errada leva a trocar
 * peça à toa. Ao incluir um código, confira em mais de uma fonte.
 */
object DtcCatalog {

    data class Info(
        val code: String,
        /** Descrição do código; null quando o app não conhece este código. */
        val title: String?,
        /** "Motor e câmbio", "Chassi", "Carroceria" ou "Rede de comunicação". */
        val system: String,
        /** Genérico (mesmo significado em todo carro) ou definido pelo fabricante. */
        val manufacturer: Boolean,
        /** Grupo pela norma, só para códigos P0/P2. */
        val group: String?,
    ) {
        /** Texto de uma linha para relatório e log. */
        fun summary(): String = title ?: buildString {
            append(if (manufacturer) "código do fabricante, sem descrição no app" else "código genérico sem descrição no app")
            group?.let { append(" · grupo: ").append(it) }
        }

        /** Detalhe da origem, para mostrar abaixo da descrição. */
        fun origin(): String = buildString {
            append(system)
            append(if (manufacturer) " · definido pelo fabricante" else " · código genérico")
            if (title != null) group?.let { append(" · ").append(it) }
        }
    }

    private val pattern = Regex("^[PCBU][0-3][0-9A-F]{3}$")

    fun describe(code: String): Info {
        val c = code.uppercase()
        if (!pattern.matches(c)) return Info(c, null, "Desconhecido", manufacturer = false, group = null)
        val system = when (c[0]) {
            'P' -> "Motor e câmbio"
            'C' -> "Chassi"
            'B' -> "Carroceria"
            else -> "Rede de comunicação"
        }
        // P: 0 e 2 são da norma, 1 do fabricante, 3 dividido (P30-P33 fabricante, P34-P39 norma).
        // C, B, U: 0 e 3 da norma, 1 e 2 do fabricante.
        val manufacturer = when (c[0]) {
            'P' -> c[1] == '1' || (c[1] == '3' && c[2] in '0'..'3')
            else -> c[1] == '1' || c[1] == '2'
        }
        // O grupo pelo terceiro caractere vale para P0 e P2; "A" (híbrido) só existe em P0Axx.
        val group = when {
            c[0] != 'P' || (c[1] != '0' && c[1] != '2') -> null
            c[2] == 'A' && c[1] != '0' -> null
            else -> groups[c[2]]
        }
        return Info(c, table[c], system, manufacturer, group)
    }

    private val groups = mapOf(
        '0' to "combustível e ar",
        '1' to "combustível e ar",
        '2' to "combustível e ar",
        '3' to "ignição e falha de combustão",
        '4' to "controle de emissões (EGR, catalisador)",
        '5' to "velocidade, marcha lenta e entradas",
        '6' to "módulo de controle e saídas",
        '7' to "transmissão",
        '8' to "transmissão",
        '9' to "transmissão",
        'A' to "sistema híbrido",
    )

    private val table: Map<String, String> = mapOf(
        // Sincronismo e combustível
        "P0016" to "Correlação entre virabrequim e comando de válvulas fora do esperado (banco 1, sensor A)",
        "P0045" to "Circuito do solenoide de controle do turbo aberto",
        "P0087" to "Pressão do rail/sistema de combustível muito baixa",
        "P0088" to "Pressão do rail/sistema de combustível muito alta",
        "P0089" to "Desempenho do regulador de pressão de combustível",
        "P0093" to "Vazamento grande no sistema de combustível",
        // Sensores de ar e temperatura
        "P0100" to "Circuito do sensor de fluxo de ar (MAF)",
        "P0101" to "Sensor de fluxo de ar (MAF) fora da faixa ou com desempenho ruim",
        "P0102" to "Sensor de fluxo de ar (MAF) com sinal baixo",
        "P0103" to "Sensor de fluxo de ar (MAF) com sinal alto",
        "P0105" to "Circuito do sensor de pressão do coletor (MAP)",
        "P0106" to "Sensor de pressão do coletor (MAP) fora da faixa ou com desempenho ruim",
        "P0107" to "Sensor de pressão do coletor (MAP) com sinal baixo",
        "P0108" to "Sensor de pressão do coletor (MAP) com sinal alto",
        "P0110" to "Circuito do sensor de temperatura do ar de admissão",
        "P0112" to "Sensor de temperatura do ar de admissão com sinal baixo",
        "P0113" to "Sensor de temperatura do ar de admissão com sinal alto",
        "P0115" to "Circuito do sensor de temperatura do motor (líquido de arrefecimento)",
        "P0116" to "Sensor de temperatura do motor fora da faixa ou com desempenho ruim",
        "P0117" to "Sensor de temperatura do motor com sinal baixo",
        "P0118" to "Sensor de temperatura do motor com sinal alto",
        "P0120" to "Circuito do sensor de posição da borboleta/pedal A",
        "P0121" to "Sensor de posição da borboleta/pedal A fora da faixa ou com desempenho ruim",
        "P0122" to "Sensor de posição da borboleta/pedal A com sinal baixo",
        "P0123" to "Sensor de posição da borboleta/pedal A com sinal alto",
        "P0128" to "Motor demora a aquecer: temperatura abaixo da do termostato",
        "P0180" to "Circuito do sensor de temperatura do combustível A",
        "P0182" to "Sensor de temperatura do combustível A com sinal baixo",
        "P0183" to "Sensor de temperatura do combustível A com sinal alto",
        "P0190" to "Circuito do sensor de pressão do rail",
        "P0191" to "Sensor de pressão do rail fora da faixa ou com desempenho ruim",
        "P0192" to "Sensor de pressão do rail com sinal baixo",
        "P0193" to "Sensor de pressão do rail com sinal alto",
        // Injetores e motor
        "P0200" to "Circuito dos injetores aberto",
        "P0201" to "Circuito do injetor do cilindro 1 aberto",
        "P0202" to "Circuito do injetor do cilindro 2 aberto",
        "P0203" to "Circuito do injetor do cilindro 3 aberto",
        "P0204" to "Circuito do injetor do cilindro 4 aberto",
        "P0217" to "Superaquecimento do motor",
        "P0219" to "Rotação do motor acima do limite",
        // Turbo
        "P0234" to "Pressão do turbo acima do limite (overboost)",
        "P0235" to "Circuito do sensor de pressão do turbo A",
        "P0237" to "Sensor de pressão do turbo A com sinal baixo",
        "P0238" to "Sensor de pressão do turbo A com sinal alto",
        "P0299" to "Pressão do turbo abaixo do esperado (underboost)",
        // Falha de combustão
        "P0300" to "Falha de combustão aleatória ou em vários cilindros",
        "P0301" to "Falha de combustão no cilindro 1",
        "P0302" to "Falha de combustão no cilindro 2",
        "P0303" to "Falha de combustão no cilindro 3",
        "P0304" to "Falha de combustão no cilindro 4",
        "P0335" to "Circuito do sensor de rotação do virabrequim A",
        "P0336" to "Sensor de rotação do virabrequim A fora da faixa ou com desempenho ruim",
        "P0340" to "Circuito do sensor de posição do comando de válvulas A (banco 1)",
        "P0380" to "Circuito das velas aquecedoras A",
        "P0381" to "Circuito da luz indicadora das velas aquecedoras",
        // Emissões
        "P0400" to "Fluxo da EGR (recirculação dos gases de escape)",
        "P0401" to "Fluxo da EGR insuficiente",
        "P0402" to "Fluxo da EGR excessivo",
        "P0403" to "Circuito de controle da válvula EGR",
        "P0404" to "Circuito da válvula EGR fora da faixa ou com desempenho ruim",
        "P0405" to "Sensor de posição da válvula EGR A com sinal baixo",
        "P0406" to "Sensor de posição da válvula EGR A com sinal alto",
        "P0420" to "Eficiência do catalisador abaixo do limite (banco 1)",
        "P0460" to "Circuito do sensor de nível de combustível A",
        "P0470" to "Sensor de pressão do escapamento A",
        "P0480" to "Circuito de controle da ventoinha 1",
        // Velocidade, tensão e módulo
        "P0500" to "Sensor de velocidade do veículo A",
        "P0560" to "Tensão do sistema elétrico",
        "P0562" to "Tensão do sistema elétrico baixa",
        "P0563" to "Tensão do sistema elétrico alta",
        "P0571" to "Circuito do interruptor do freio A",
        "P0600" to "Falha na comunicação serial do módulo",
        "P0601" to "Erro de verificação na memória do módulo de controle",
        "P0605" to "Erro na memória ROM do módulo de controle",
        "P0606" to "Falha no processador do módulo de controle",
        "P0627" to "Circuito de controle da bomba de combustível A aberto",
        "P0641" to "Circuito da tensão de referência dos sensores A aberto",
        "P0651" to "Circuito da tensão de referência dos sensores B aberto",
        // Transmissão
        "P0700" to "O módulo do câmbio pediu a luz de falha (leia os códigos do câmbio)",
        "P0705" to "Circuito do sensor de posição da alavanca (PRNDL)",
        "P0710" to "Circuito do sensor de temperatura do fluido do câmbio A",
        "P0715" to "Circuito do sensor de rotação de entrada/turbina A",
        "P0720" to "Circuito do sensor de rotação de saída",
        "P0730" to "Relação de marcha incorreta",
        "P0740" to "Circuito da embreagem do conversor de torque aberto",
        "P0741" to "Embreagem do conversor de torque com desempenho ruim ou presa desligada",
        "P0750" to "Solenoide de troca de marcha A",
        "P0755" to "Solenoide de troca de marcha B",
        "P0760" to "Solenoide de troca de marcha C",
        "P0765" to "Solenoide de troca de marcha D",
        // P2xxx
        "P2002" to "Eficiência do filtro de partículas (DPF) abaixo do limite (banco 1)",
        "P2146" to "Circuito de alimentação do grupo A de injetores aberto",
        "P2149" to "Circuito de alimentação do grupo B de injetores aberto",
        "P2227" to "Sensor de pressão barométrica A fora da faixa ou com desempenho ruim",
        "P2228" to "Sensor de pressão barométrica A com sinal baixo",
        "P2229" to "Sensor de pressão barométrica A com sinal alto",
        "P2263" to "Desempenho do sistema de pressão do turbo",
        "P2413" to "Desempenho do sistema EGR",
        "P2425" to "Circuito de controle da válvula de arrefecimento da EGR aberto",
        "P2426" to "Circuito de controle da válvula de arrefecimento da EGR com sinal baixo",
        "P2427" to "Circuito de controle da válvula de arrefecimento da EGR com sinal alto",
        "P2457" to "Desempenho do sistema de arrefecimento da EGR",
        // Rede
        "U0001" to "Rede CAN de alta velocidade",
        "U0100" to "Sem comunicação com o módulo do motor (ECM/PCM A)",
        "U0101" to "Sem comunicação com o módulo do câmbio (TCM)",
    )
}
