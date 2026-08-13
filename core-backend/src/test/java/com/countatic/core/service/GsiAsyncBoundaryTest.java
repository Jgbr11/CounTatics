package com.countatic.core.service;

import com.countatic.core.repository.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A prova que o teste unitário não consegue dar.
 *
 * <p>{@link GsiEventServiceTest} verifica a <i>delegação</i> com um mock, e
 * mock não é proxy: ele passaria igual se o {@code @Async} tivesse sumido. O
 * que decide se o envio realmente sai da thread do Tomcat é a existência do
 * proxy AOP em torno do bean — e isso só o contexto do Spring pode mostrar.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GsiAsyncBoundaryTest {

    @Autowired
    private GsiPreliminaryReportService preliminaryReportService;

    @MockBean
    private PlayerRepository playerRepository;

    @MockBean
    private MatchDiscoveryScheduler discoveryScheduler;

    @Test
    @DisplayName("O bean do relatório preliminar é um proxy AOP — sem ele o @Async é decoração "
            + "e o read timeout de 90 s do bot travaria a resposta de 5 s ao CS2")
    void beanDoPreliminarEhProxyAop() {
        assertThat(AopUtils.isAopProxy(preliminaryReportService))
                .as("@EnableAsync + @Async deveriam envolver o bean num proxy")
                .isTrue();
    }
}
