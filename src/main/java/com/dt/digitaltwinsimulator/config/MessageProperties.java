package com.dt.digitaltwinsimulator.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

@Getter
@Setter
@Component
@ConfigurationProperties("simulator.message")
public class MessageProperties {
    private LinkedHashMap<String, String> fileDataExtraFields = defaults();

    private static LinkedHashMap<String, String> defaults() {
        var fields = new LinkedHashMap<String, String>();
        fields.put("works_code", "K");
        fields.put("sndr_inform_edit_pgm_id", "");
        fields.put("eai_interface_id", "");
        fields.put("interface_data_dir_actual_type", "");
        fields.put("interface_data_ocr_res_flag", "");
        fields.put("interface_data_send_seq", "0");
        fields.put("interface_data_upd_tp", "");
        fields.put("interface_data_t_len", "760");
        fields.put("attribute", " ");
        fields.put("bsc_gw_data_attr", " ");
        fields.put("it_com_eai_ifc_var_item_usg_f", " ");
        return fields;
    }
}
