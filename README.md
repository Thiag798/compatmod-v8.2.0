# CompatMod v8.2.0

O **CompatMod** é um mod de compatibilidade para Minecraft Forge 1.21.1. Ele aplica ajustes controlados durante o processo de bake dos modelos para reduzir conflitos visuais e problemas de renderização causados por modelos de blocos de diferentes mods.

A versão 8.2.0 concentra-se em robustez, diagnóstico e recuperação segura. O projeto não utiliza um sistema Mixin próprio para aplicar os patches descritos neste README. O fluxo atual usa eventos do Forge durante o bake dos modelos e envolve os modelos resultantes com `BakedModelWrapper` quando é necessário alterar propriedades de renderização.

## Requisitos

| Componente | Versão/configuração |
|---|---|
| Minecraft | 1.21.1 |
| Forge | **52.1.0**, versão usada no desenvolvimento e na validação do projeto |
| Java | 21 |

A configuração de build declara uma faixa de carregamento Forge iniciada em 52, mas a versão efetivamente configurada no projeto é **52.1.0**. Versões diferentes do Forge não devem ser consideradas compatíveis sem validação adicional.

## Funcionalidades

O CompatMod mantém um registro de patches aplicáveis a modelos e oferece os seguintes comportamentos:

- **Correção de modelos de vidro:** identifica modelos relacionados a vidro e painéis de vidro e pode ajustar culling, transparência e ambient occlusion.
- **Controle de ambient occlusion:** desativa ambient occlusion em modelos de folhas, vegetação e determinados modelos florais quando o patch correspondente é aplicado.
- **Compatibilidade de modelos:** permite aplicar regras específicas a modelos cujos caminhos correspondam aos predicados registrados.
- **Cache de modelos:** acompanha modelos processados para reduzir trabalho repetido e disponibiliza estatísticas para diagnóstico.
- **Blacklist:** permite excluir modelos específicos do processamento.
- **Safe mode:** suspende os patches quando o modo seguro está ativo, permitindo investigar problemas sem remover o mod da instância.
- **Log de transformações:** registra as transformações aplicadas no arquivo de configuração do mod quando o logging correspondente está habilitado.

## Como o patch de modelos funciona

Durante o carregamento dos modelos, o `ModelBakeListener` observa os modelos produzidos pelo Forge e compara o identificador de cada modelo com os patches registrados em `CompatRegistry`. Quando há correspondência e o modelo não está na blacklist, o CompatMod aplica os ajustes configurados.

Para alterações que precisam afetar propriedades de renderização, o mod utiliza `CompatBakedModel`, baseado no utilitário público `BakedModelWrapper` do Forge. O wrapper delega o comportamento não alterado ao modelo original e protege chamadas de renderização sensíveis contra falhas de linkage ou runtime, evitando que um problema de compatibilidade derrube o jogo sem registro.

Esse mecanismo é diferente de uma injeção Mixin. Portanto, instruções ou diagnósticos que pressupõem um arquivo de configuração Mixin específico não se aplicam à implementação atual deste projeto.

## Instalação

Baixe o JAR de produção correspondente ao projeto e coloque-o na pasta `mods` de uma instância Minecraft Forge 1.21.1 usando Java 21. Inicie o jogo e consulte o log para verificar se os modelos foram processados.

Para investigar um problema, mantenha uma cópia do `latest.log` e do arquivo de configuração do CompatMod. Se o problema desaparecer com o safe mode ativado, reative os patches individualmente ou use a blacklist para isolar o modelo responsável.

## Comandos

Os comandos abaixo são registrados sob `/compatmod` e devem ser executados por uma fonte com permissão adequada:

| Comando | Função |
|---|---|
| `/compatmod status` | Exibe o estado do mod, a quantidade de patches carregados e quantos foram aplicados na sessão. |
| `/compatmod cache` | Exibe estatísticas do cache de modelos, incluindo modelos armazenados e processados. |
| `/compatmod reload` | Recarrega a configuração e o registro de patches. |
| `/compatmod safemode` | Alterna o safe mode. Quando ativo, os patches ficam suspensos. |
| `/compatmod blacklist add <model>` | Adiciona um modelo à blacklist; por exemplo, `/compatmod blacklist add minecraft:block/stone`. |
| `/compatmod blacklist remove <model>` | Remove um modelo da blacklist. |
| `/compatmod blacklist list` | Lista os modelos atualmente bloqueados. |
| `/compatmod patches` | Lista os patches ativos no registro do CompatMod. |

O argumento `<model>` deve ser informado como identificador de recurso, normalmente no formato `namespace:path`, como `minecraft:block/stone` ou `meumod:block/meu_modelo`.

## Compilação

O projeto utiliza Gradle e inclui o wrapper para Linux e Windows. Para compilar uma versão limpa, execute:

```bash
./gradlew clean build
```

No Windows, utilize:

```bat
gradlew.bat clean build
```

O JAR de produção e o JAR de fontes são gerados em `build/libs/`. O workflow de CI utiliza Java 21 e executa o build em ambiente Ubuntu quando uma release é criada.

## Testes

Os testes unitários podem ser executados com:

```bash
./gradlew test
```

A suíte atual valida principalmente a seleção dos patches registrados e a presença dos componentes de safe mode. Esses testes não substituem uma validação de integração em uma instância real de Minecraft Forge. A compatibilidade com outras versões do Forge ou com combinações específicas de mods deve ser confirmada separadamente.

## Diagnóstico e limitações

O CompatMod foi projetado para reduzir conflitos de modelos, mas não pode garantir compatibilidade universal com todos os mods, resource packs, shaders ou versões do Forge. Em caso de falha, primeiro consulte o log, ative o safe mode e teste a blacklist para identificar o modelo ou patch envolvido.

Ao relatar um problema, informe a versão do Minecraft, a versão exata do Forge, a versão do Java, a lista mínima de mods envolvidos e os trechos relevantes de `latest.log` e `compatmod-transforms.log`, quando este último estiver habilitado.

## Licença

A licença aplicável deve ser consultada no arquivo de licença distribuído com o projeto, quando presente. O README não presume uma licença específica na ausência desse arquivo.

---

Desenvolvido para facilitar a identificação e o tratamento de incompatibilidades de modelos no ecossistema Minecraft Forge.
