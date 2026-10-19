package com.academy.paybridge.compliance;

import com.academy.paybridge.compliance.service.OfacSanctionsList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses rows in the exact format of OFAC's SDN.CSV and ALT.CSV (copied from the published files). */
class OfacSanctionsListTest {

    private static final String SDN = """
            306,"BANCO NACIONAL DE CUBA",-0- ,"CUBA",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,"a.k.a. 'BNC'."\r
            6365,"BIN LADIN, Usama bin Muhammad bin Awad","individual","SDGT",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,"DOB 30 Jul 1957; alt. DOB 1958; POB Jeddah, Saudi Arabia"\r
            2676,"AL ZAWAHIRI, Dr. Ayman","individual","SDGT","Operational and Military Leader of JIHAD GROUP",-0- ,-0- ,-0- ,-0- ,-0- ,-0- ,"DOB 19 Jun 1951; POB Giza, Egypt"\r
            \u001a""";
    private static final String ALT = """
            306,220,"aka","NATIONAL BANK OF CUBA",-0- \r
            6365,5001,"aka","BIN LADEN, Osama",-0- \r
            """;

    private final OfacSanctionsList list = loaded();

    @Test
    void parsesNamesAliasesAndDatesOfBirth() {
        assertThat(list.status().primaryNames()).isEqualTo(3);
        assertThat(list.status().aliases()).isEqualTo(2);
        assertThat(list.screen("Usama bin Ladin")).singleElement().satisfies(h -> {
            assertThat(h.entNum()).isEqualTo(6365);
            assertThat(h.dob()).isEqualTo("30 Jul 1957");
            assertThat(h.program()).isEqualTo("SDGT");
        });
    }

    @Test
    void matchingIgnoresOrderCaseAccentsAndOneLetterSpellingDifferences() {
        assertThat(list.screen("osama bin laden")).extracting(OfacSanctionsList.Hit::entNum).containsExactly(6365);
        assertThat(list.screen("Ayman al-Zawahiri")).extracting(OfacSanctionsList.Hit::entNum).containsExactly(2676);
        assertThat(list.screen("Aymán ZAWÁHIRI")).isNotEmpty();
        assertThat(list.screen("National Bank of Cuba")).extracting(OfacSanctionsList.Hit::alias).containsExactly(true);
    }

    @Test
    void ordinaryNamesAndSingleWordsDoNotHit() {
        assertThat(list.screen("Ada Obi")).isEmpty();
        assertThat(list.screen("Grace Okafor")).isEmpty();
        assertThat(list.screen("Usama")).isEmpty();          // one word is not enough evidence
        assertThat(list.screen("Usama Okafor")).isEmpty();   // every word must be on the listed name
        assertThat(list.screen("Bin Al")).isEmpty();         // filler words are not evidence
    }

    private static OfacSanctionsList loaded() {
        OfacSanctionsList l = new OfacSanctionsList();
        l.load(SDN, ALT, "test");
        return l;
    }
}
