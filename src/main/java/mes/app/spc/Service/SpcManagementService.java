package mes.app.spc.Service;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.RequiredArgsConstructor;
import mes.domain.entity.Tb_spc_std01;
import mes.domain.repository.Tb_spc_std01Repository;
import mes.domain.services.SqlRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SpcManagementService {

    private final Tb_spc_std01Repository repository;

    @Autowired
    SqlRunner sqlRunner;

    //목록 조회
    // [변경] 기존 품목명(srchMat)·레시피(srchRecipe) 필터 -> 공정(srchProcess)·측정항목(srchMeasure) 필터.
    //        나중에 여러 공정 공용으로 되살릴 경우 srchMat/srchRecipe 조건을 다시 추가하면 된다.
    public List<Map<String, Object>> getList(String srchProcess, String srchMeasure) {

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("srchProcess", srchProcess);
        params.addValue("srchMeasure", srchMeasure);

        StringBuilder sql = new StringBuilder("""
        SELECT a.*
        , b."Value" AS measure_cycle_unit_name
        FROM tb_spc_std01 a
        LEFT JOIN sys_code b
           ON a.measure_cycle_unit = b."Code"
          AND b."CodeType" = 'measure_cycle_unit'
        WHERE 1=1
        """);

        // 공정: process_code 정확매칭 (콤보에서 코드값을 넘김)
        if (StringUtils.isNotEmpty(srchProcess)) {
            sql.append(" AND a.process_code = :srchProcess ");
        }

        // 측정항목: measure_code 정확매칭
        if (StringUtils.isNotEmpty(srchMeasure)) {
            sql.append(" AND a.measure_code = :srchMeasure ");
        }

        sql.append(" ORDER BY id DESC ");

        return sqlRunner.getRows(sql.toString(), params);
    }


    public Optional<Tb_spc_std01> findById(Integer id) {
        return repository.findById(id);
    }

    /**
     * 유니크 키(process_code + measure_code + item_code)로 기존 스펙 조회.
     * 화면에서 id 가 넘어오지 않아도 신규 INSERT 로 처리해 유니크 제약(ux_tb_spc_std01)을
     * 위반하는 것을 막기 위함. item_code 는 NULL/빈문자열을 같은 것으로 취급한다.
     */
    public Optional<Tb_spc_std01> findByKey(String processCode, String measureCode, String itemCode) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("pc", processCode);
        p.addValue("mc", measureCode);
        p.addValue("ic", (itemCode == null || itemCode.isBlank()) ? null : itemCode);
        Map<String, Object> row = sqlRunner.getRow("""
            SELECT id FROM tb_spc_std01
             WHERE process_code = :pc
               AND measure_code = :mc
               AND COALESCE(NULLIF(item_code, ''), '') = COALESCE(:ic, '')
             ORDER BY id
             LIMIT 1
            """, p);
        if (row == null || row.get("id") == null) return Optional.empty();
        return repository.findById(((Number) row.get("id")).intValue());
    }

    public Tb_spc_std01 save(Tb_spc_std01 entity) {
        return repository.save(entity);
    }

    public void delete(Integer id) {
        repository.deleteById(id);
    }
}
