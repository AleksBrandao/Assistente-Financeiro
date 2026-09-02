# Assistente Financeiro

Aplicativo Android para organizar movimentações financeiras a partir de notificações autorizadas,
lançamentos manuais e importações do Mobills. Os dados são armazenados localmente no aparelho.

## Recursos

- extrato mensal com valores realizados e previstos;
- contas bancárias, cartões, faturas, pagamentos e transferências;
- compras de cartão consolidadas pelo vencimento da fatura;
- três datas financeiras: vencimento, pagamento previsto e pagamento realizado;
- pesquisa por descrição, período e situação;
- categorias automáticas e edição manual;
- resumo anual e planejamento de 30, 60 e 90 dias;
- orçamento total e por categoria;
- importação do Mobills com prévia e análise de duplicidades;
- backup, restauração, exportação CSV e lixeira recuperável.

## Desenvolvimento

Requisitos:

- Android Studio ou JDK 17 ou superior;
- Android SDK 35;
- Gradle Wrapper incluído no projeto.

```powershell
$env:JAVA_HOME = "C:\Users\aleks\.jdks\jbr-21.0.11"
.\gradlew.bat test
.\gradlew.bat installDebug
```

## Distribuição

- cada pull request executa os testes e gera um APK assinado temporário em **Actions → Artifacts**;
- o workflow **Publicar APK Android** cria ou atualiza a Release correspondente à versão;
- as versões publicadas ficam disponíveis em [Releases](https://github.com/AleksBrandao/Assistente-Financeiro/releases).

Consulte também [DISTRIBUICAO_ANDROID.md](DISTRIBUICAO_ANDROID.md).

## Privacidade

O aplicativo não usa Open Finance nesta etapa. A leitura de notificações depende de autorização
explícita no Android e o usuário pode criar um backup antes de trocar de aparelho.
