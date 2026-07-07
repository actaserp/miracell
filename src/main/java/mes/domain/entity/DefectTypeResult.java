package mes.domain.entity;

import lombok.*;

import javax.persistence.*;
import java.sql.Timestamp;

@Getter
@Setter
@Entity
@Table(name = "defect_type_result")
public class DefectTypeResult extends AbstractAuditModel{

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	// ── 다형성 소스: 세척이면 ('mat_produce', mp_id) ──
	@Column(name = "SourceDataPk")
	private Integer sourceDataPk;

	@Column(name = "SourceTableName")
	private String sourceTableName;

	// ── 유형 = defect_type (proc_defect_type 로 공정별 제한) ──
	@Column(name = "DefectType_id")
	private Integer defectTypeId;

	@Column(name = "DefectQty")
	private Double defectQty;

	@Column(name = "Description")
	private String description;

	// ── ★ 추가: 부적합 대상 자재(부품) ──
	@Column(name = "Material_id")
	private Integer materialId;

	// (선택) 어느 입고 로트였나 — 마이그레이션에서 컬럼 뺐으면 이 필드도 삭제
	@Column(name = "MatLot_id")
	private Integer matLotId;

}